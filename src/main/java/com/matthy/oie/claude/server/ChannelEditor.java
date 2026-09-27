package com.matthy.oie.claude.server;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.Rule;

/**
 * Turns the OIE XML that Claude writes (filter rules, transformer steps, whole channels) into
 * something OIE's own serializer can read safely, and describes the result for the approval dialog.
 * Part of {@link OieTools}; free of engine calls so it can be tested on its own.
 */
final class ChannelEditor {

    /**
     * Elements that hold an OIE object without a class name in their tag. OIE's serializer reads an
     * object without a version attribute as version 3.0.0 and migrates it, which can garble XML
     * that Claude wrote in the current format.
     */
    private static final Set<String> OBJECT_ELEMENTS = Set.of("channel", "connector", "sourceConnector", "filter", "transformer", "responseTransformer",
            "sourceConnectorProperties", "destinationConnectorProperties", "properties", "inboundProperties", "outboundProperties",
            "serializationProperties", "deserializationProperties", "batchProperties", "responseGenerationProperties", "responseValidationProperties",
            "iteratorProperties", "exportData");

    /** Credentials in connector properties: Claude cannot set them, and has only seen them masked. */
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(<([\\w.]*(?:password|passphrase|secret|token|apikey)[\\w.]*)(?:\\s[^>]*)?>)[^<]+(</\\2>)");

    private ChannelEditor() {}

    /**
     * The elements of a filter or transformer as a list the serializer reads: accepts
     * &lt;elements&gt;...&lt;/elements&gt;, &lt;list&gt;...&lt;/list&gt; or the element XML itself
     * (one or more elements, without a wrapper), and gives every object a version attribute.
     */
    static String elementList(String xml, String version) throws Exception {
        String trimmed = xml == null ? "" : xml.trim();
        if (trimmed.isEmpty() || trimmed.matches("(?s)<(elements|list)\\s*/>")) {
            return "<list/>";
        }
        Document doc = parse("<wrapper>" + stripDeclaration(trimmed) + "</wrapper>");
        Element wrapper = doc.getDocumentElement();
        List<Element> children = children(wrapper);
        Element list = doc.createElement("list");
        if (children.size() == 1 && (children.get(0).getTagName().equals("elements") || children.get(0).getTagName().equals("list"))) {
            children = children(children.get(0));
        }
        for (Element child : children) {
            list.appendChild(child);
        }
        addVersions(list, version, false);
        return write(list);
    }

    /** A whole channel as the serializer reads it, every object with a version attribute. */
    static String channel(String xml, String version) throws Exception {
        Document doc = parse(stripDeclaration(xml == null ? "" : xml.trim()));
        Element root = doc.getDocumentElement();
        if (!root.getTagName().equals("channel")) {
            throw new IllegalArgumentException("The channel XML must have <channel> as its root element (the format of oie_get_channel), not <" + root.getTagName() + ">.");
        }
        addVersions(root, version, true);
        return write(root);
    }

    /** Empties every credential element; returns the cleared element names in order. */
    static String clearCredentials(String xml, List<String> cleared) {
        Matcher m = CREDENTIAL.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            cleared.add(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** One line per element, e.g. "  1. [Mapper] 'patientName'" or "  2. OR [Rule Builder] 'Is ADT' (disabled)". */
    static String describe(List<? extends FilterTransformerElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return "  (none)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            FilterTransformerElement e = elements.get(i);
            sb.append("  ").append(i).append(". ");
            if (e instanceof Rule && i > 0 && ((Rule) e).getOperator() != null && ((Rule) e).getOperator() != Rule.Operator.NONE) {
                sb.append(((Rule) e).getOperator()).append(' ');
            }
            sb.append('[').append(e.getType()).append("] '").append(e.getName() == null ? "" : e.getName()).append('\'');
            if (!e.isEnabled()) {
                sb.append(" (disabled)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The JavaScript OIE generates for an element: shows what a Mapper or Rule Builder element really does. */
    static String script(FilterTransformerElement element) {
        try {
            String script = element.getScript(false);
            return script == null ? "" : script.trim();
        } catch (Exception e) {
            return "(no script: " + e.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------------ XML

    private static void addVersions(Element element, String version, boolean isRoot) {
        String tag = element.getTagName();
        boolean isObject = isRoot || tag.indexOf('.') > 0 || element.hasAttribute("class") || OBJECT_ELEMENTS.contains(tag);
        if (isObject && !element.hasAttribute("version")) {
            element.setAttribute("version", version);
        }
        for (Element child : children(element)) {
            addVersions(child, version, false);
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private static String stripDeclaration(String xml) {
        return xml.replaceFirst("^<\\?xml[^>]*\\?>\\s*", "");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try {
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (org.xml.sax.SAXParseException e) {
            throw new IllegalArgumentException("The XML is not well-formed (line " + e.getLineNumber() + "): " + e.getMessage());
        }
    }

    private static String write(Element element) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = factory.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(element), new StreamResult(out));
        return out.toString();
    }
}
