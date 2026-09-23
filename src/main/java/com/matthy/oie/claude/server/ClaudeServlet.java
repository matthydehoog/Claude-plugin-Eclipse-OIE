package com.matthy.oie.claude.server;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;

public class ClaudeServlet extends MirthServlet implements ClaudeServletInterface {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ClaudeServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    public String chat(String body) {
        JsonNode in = parse(body);
        String message = in.path("message").asText("");
        if (message.isBlank()) {
            throw new MirthApiException(Response.status(Response.Status.BAD_REQUEST).entity("Leeg bericht.").build());
        }
        if (doesUserHaveChannelRestrictions()) {
            // Claude's tools see every channel, so users limited to some channels cannot use it.
            throw new MirthApiException(Response.status(Response.Status.FORBIDDEN).entity("De Claude-assistent is niet beschikbaar voor gebruikers met channel-beperkingen.").build());
        }
        try {
            ChatJob job = service().startChat(getCurrentUserId(), in.path("conversationId").asText(null), message, in.path("context").asText(null));
            ObjectNode out = MAPPER.createObjectNode();
            out.put("conversationId", job.conversation.id);
            out.put("jobId", job.id);
            return out.toString();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new MirthApiException(Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build());
        }
    }

    @Override
    public String getJob(String jobId, int after) {
        return job(jobId).toJson(after).toString();
    }

    @Override
    public String confirm(String jobId, String body) {
        JsonNode in = parse(body);
        try {
            String result = service().decide(job(jobId), in.path("actionId").asText(""), in.path("approved").asBoolean(false), context);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("result", result);
            return out.toString();
        } catch (IllegalStateException e) {
            throw new MirthApiException(Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build());
        }
    }

    @Override
    public String cancel(String jobId) {
        job(jobId).cancel();
        return "{}";
    }

    @Override
    public String getSettings() {
        ObjectNode out = MAPPER.createObjectNode();
        service().settings().writeTo(out);
        return out.toString();
    }

    @Override
    public String setSettings(String body) {
        try {
            Settings updated = service().settings().merge(parse(body));
            new Masker(updated.maskPatterns); // validates the patterns before saving
            plugin().save(updated);
            return getSettings();
        } catch (IllegalArgumentException e) {
            throw new MirthApiException(Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build());
        } catch (Exception e) {
            throw new MirthApiException(e);
        }
    }

    private ChatJob job(String jobId) {
        try {
            return service().job(jobId, getCurrentUserId());
        } catch (IllegalArgumentException e) {
            throw new MirthApiException(Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build());
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = MAPPER.readTree(body == null ? "{}" : body);
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            throw new MirthApiException(Response.status(Response.Status.BAD_REQUEST).entity("Ongeldige JSON: " + e.getMessage()).build());
        }
    }

    private static ClaudeServicePlugin plugin() {
        return (ClaudeServicePlugin) ControllerFactory.getFactory().createExtensionController().getServicePlugins().get(PLUGIN_POINT);
    }

    private static AssistantService service() {
        return plugin().service();
    }
}
