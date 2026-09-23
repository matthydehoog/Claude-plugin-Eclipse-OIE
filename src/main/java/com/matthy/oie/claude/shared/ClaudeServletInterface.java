package com.matthy.oie.claude.shared;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

/**
 * REST API of the Claude Assistant plugin. Bodies and responses are JSON strings (text/plain), so no
 * plugin classes have to pass through the engine's XStream serializer.
 */
@Path("/extensions/claude")
@Tag(name = "Extension Services")
@Consumes(MediaType.TEXT_PLAIN)
@Produces(MediaType.TEXT_PLAIN)
public interface ClaudeServletInterface extends BaseServletInterface {

    String PLUGIN_POINT = "Claude Assistant";

    String PERMISSION_USE = "Use Claude Assistant";
    String PERMISSION_ACTIONS = "Run Claude Assistant actions";
    String PERMISSION_SETTINGS = "Manage Claude Assistant settings";

    @POST
    @Path("/chat")
    @Operation(summary = "Sends a user message to Claude and starts a job. Body: {conversationId?, message, context?}. Returns {conversationId, jobId}.")
    @MirthOperation(name = "claudeChat", display = "Chat with Claude", permission = PERMISSION_USE, type = ExecuteType.ASYNC, auditable = false)
    String chat(@Param("body") @Parameter(description = "Chat request as JSON.", required = true) String body) throws ClientException;

    @GET
    @Path("/jobs/{jobId}")
    @Operation(summary = "Returns job status and the events after the given sequence number.")
    @MirthOperation(name = "claudeGetJob", display = "Get Claude job", permission = PERMISSION_USE, type = ExecuteType.ASYNC, auditable = false)
    String getJob(// @formatter:off
            @Param("jobId") @Parameter(description = "Job ID.", required = true) @PathParam("jobId") String jobId,
            @Param("after") @Parameter(description = "Only events with a greater sequence number are returned.") @QueryParam("after") int after) throws ClientException;
    // @formatter:on

    @POST
    @Path("/jobs/{jobId}/confirm")
    @Operation(summary = "Approves or rejects the action the job is waiting for. Body: {actionId, approved}.")
    @MirthOperation(name = "claudeConfirmAction", display = "Run Claude action", permission = PERMISSION_ACTIONS, type = ExecuteType.ASYNC)
    String confirm(// @formatter:off
            @Param("jobId") @Parameter(description = "Job ID.", required = true) @PathParam("jobId") String jobId,
            @Param("body") @Parameter(description = "Decision as JSON.", required = true) String body) throws ClientException;
    // @formatter:on

    @POST
    @Path("/jobs/{jobId}/cancel")
    @Operation(summary = "Stops a running job.")
    @MirthOperation(name = "claudeCancelJob", display = "Cancel Claude job", permission = PERMISSION_USE, type = ExecuteType.ASYNC, auditable = false)
    String cancel(@Param("jobId") @Parameter(description = "Job ID.", required = true) @PathParam("jobId") String jobId) throws ClientException;

    @GET
    @Path("/settings")
    @Operation(summary = "Returns the plugin settings. The API key is never returned, only whether one is set.")
    @MirthOperation(name = "claudeGetSettings", display = "Get Claude settings", permission = PERMISSION_USE, auditable = false)
    String getSettings() throws ClientException;

    @PUT
    @Path("/settings")
    @Operation(summary = "Updates the plugin settings. An absent or empty apiKey keeps the current key.")
    @MirthOperation(name = "claudeSetSettings", display = "Set Claude settings", permission = PERMISSION_SETTINGS)
    String setSettings(@Param("body") @Parameter(description = "Settings as JSON.", required = true) String body) throws ClientException;
}
