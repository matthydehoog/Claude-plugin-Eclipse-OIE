/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.function.Function;

import javax.swing.ImageIcon;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.BadLocationException;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import com.mirth.connect.client.ui.components.MirthTreeTable;
import com.mirth.connect.client.ui.components.rsta.MirthRSyntaxTextArea;

/**
 * Adds "Explain with AI Assistant" to the right-click menu of OIE's JavaScript editors. OIE has no
 * hook for that menu, so the item is added the first time the user presses the mouse in an editor
 * (each editor creates its menu lazily), and shown only where {@link #where} finds a channel, code
 * template or the global scripts around the editor.
 */
final class ExplainCodeMenu implements AWTEventListener {

    /** Longest piece of code put in the question; the rest is left out with a note. */
    static final int MAX_CODE_CHARS = 20_000;

    /** Same text as EXPLAIN_CODE_REQUEST in webadmin/web/plugin.js. */
    static final String EXPLAIN_CODE_REQUEST = "what it does step by step, which message data and maps it reads and writes, and anything that looks wrong or risky. "
            + "It is copied from the editor, so it may include unsaved changes.";

    private static final String ITEM = "claude-assistant.explainCode";
    private static final String SEPARATOR = "claude-assistant.explainCodeSeparator";

    /** Where an editor is: the chat context and the editor's place, for example "transformer step #2 'Map PID'". */
    static final class Target {
        final String context;
        final String location;

        Target(String context, String location) {
            this.context = context;
            this.location = location;
        }
    }

    /** What to do with the request; the plugin opens the chat window. */
    interface Opener {
        void open(String context, String question);
    }

    private final Function<MirthRSyntaxTextArea, Target> where;
    private final Opener opener;
    private final ImageIcon icon;

    ExplainCodeMenu(Function<MirthRSyntaxTextArea, Target> where, Opener opener, ImageIcon icon) {
        this.where = where;
        this.opener = opener;
        this.icon = icon;
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (event.getID() != MouseEvent.MOUSE_PRESSED || !(event.getSource() instanceof MirthRSyntaxTextArea)) {
            return;
        }
        MirthRSyntaxTextArea area = (MirthRSyntaxTextArea) event.getSource();
        try {
            prepare(area);
        } catch (RuntimeException e) {
            // Never break OIE's own editor menu.
        }
    }

    /** Adds the item to the editor's menu once, and shows it only for JavaScript in a known place. */
    private void prepare(MirthRSyntaxTextArea area) {
        JPopupMenu menu = area.getPopupMenu();
        if (menu == null) {
            return;
        }
        JMenuItem item = (JMenuItem) area.getClientProperty(ITEM);
        Component separator = (Component) area.getClientProperty(SEPARATOR);
        if (item == null) {
            item = new JMenuItem("Explain with AI Assistant", icon);
            item.setToolTipText("Let the AI Assistant explain the selected code, or the whole script without a selection.");
            item.addActionListener(e -> explain(area));
            separator = new JPopupMenu.Separator();
            menu.insert(item, 0);
            menu.insert(separator, 1);
            area.putClientProperty(ITEM, item);
            area.putClientProperty(SEPARATOR, separator);
        }
        boolean show = SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT.equals(area.getSyntaxEditingStyle()) && where.apply(area) != null;
        item.setVisible(show);
        separator.setVisible(show);
    }

    private void explain(MirthRSyntaxTextArea area) {
        Target target = where.apply(area);
        if (target == null) {
            return;
        }
        int start = area.getSelectionStart();
        int end = area.getSelectionEnd();
        boolean selection = end > start;
        String code = selection ? area.getEOLFixedSelectedText() : area.getEOLFixedText();
        int firstLine = 0;
        int lastLine = 0;
        if (selection) {
            try {
                firstLine = area.getLineOfOffset(start) + 1;
                // A selection that ends at the start of a line does not include that line.
                lastLine = area.getLineOfOffset(Math.max(start, end - 1)) + 1;
            } catch (BadLocationException e) {
                firstLine = 0;
            }
        }
        opener.open(target.context, request(target.location, code, selection, firstLine, lastLine));
    }

    /** The question: what to explain, then the code itself. Line numbers count from 1; 0 means unknown. */
    static String request(String location, String code, boolean selection, int firstLine, int lastLine) {
        String text = code == null ? "" : code.strip();
        String lines = firstLine <= 0 ? "the selected code" : firstLine == lastLine ? "line " + firstLine : "lines " + firstLine + "-" + lastLine;
        String what = selection ? lines + " of the " + location : "the whole " + location;
        String clipped = text.length() > MAX_CODE_CHARS
                ? text.substring(0, MAX_CODE_CHARS) + "\n// ... " + (text.length() - MAX_CODE_CHARS) + " more characters left out"
                : text;
        return "Explain this code (" + what + "): " + EXPLAIN_CODE_REQUEST + "\n\n"
                + "```javascript\n" + (clipped.isEmpty() ? "// (empty)" : clipped) + "\n```";
    }

    /** "#2 'Map PID'" for the selected row of a filter or transformer table, or null. */
    static String selectedElement(Container editorPane) {
        MirthTreeTable table = find(editorPane, MirthTreeTable.class);
        if (table == null || table.getSelectedRow() < 0) {
            return null;
        }
        int row = table.getSelectedRow();
        String number = null;
        String name = null;
        for (int c = 0; c < table.getColumnCount(); c++) {
            Object value = table.getValueAt(row, c);
            if ("#".equals(table.getColumnName(c)) && value != null) {
                number = value.toString();
            } else if ("Name".equals(table.getColumnName(c)) && value != null) {
                name = value.toString();
            }
        }
        if (number == null && name == null) {
            return null;
        }
        return (number == null ? "" : "#" + number + " ") + (name == null ? "" : "'" + name + "'");
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T found = find((Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
