package com.matthy.oie.claude.engine;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.organization.apikeys.ApiKeyListParams;
import com.anthropic.models.beta.organization.apikeys.BetaApiKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.SpendReader;

/**
 * Month-to-date spend from the Usage and Cost Admin API. The API key and workspace lookups use the
 * SDK; the cost report has no SDK binding yet, so it is a plain HTTP call.
 */
public class SdkSpendReader implements SpendReader {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(SdkModelLoop.TIMEOUT.connect()).build();

    @Override
    public String read(String adminKey, String pluginApiKey) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(SdkSpendReader.class.getClassLoader());
        try {
            return report(adminKey, pluginApiKey).toString();
        } catch (AnthropicServiceException e) {
            // The API key lookup goes through the SDK; give its errors the same wording as the cost report.
            throw new IllegalStateException(describe(e.statusCode(), e.body().toString()));
        } catch (AnthropicIoException | IOException e) {
            throw new IllegalStateException(SdkModelLoop.unreachable(e));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private ObjectNode report(String adminKey, String pluginApiKey) throws Exception {
        String baseUrl = System.getProperty("oie.claude.baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        baseUrl = baseUrl.trim().replaceAll("/+$", "");

        ObjectNode out = MAPPER.createObjectNode();
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        out.put("monthStart", monthStart.toString());
        out.put("currency", "USD");

        // Which workspace does the plugin's own key belong to? Matched on the key's redacted hint.
        String workspaceId = null;
        boolean defaultWorkspace = false;
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(adminKey).baseUrl(baseUrl).timeout(SdkModelLoop.TIMEOUT).maxRetries(SdkModelLoop.MAX_RETRIES).build();
        try {
            if (pluginApiKey != null && !pluginApiKey.isEmpty()) {
                for (BetaApiKey key : client.beta().organization().apiKeys().list(ApiKeyListParams.builder().limit(1000L).build()).autoPager()) {
                    if (matches(key.partialKeyHint().orElse(null), pluginApiKey)) {
                        out.put("pluginKeyName", key.name());
                        if (key.scope().isWorkspace()) {
                            workspaceId = key.scope().workspace().get().workspaceId();
                            // The deprecated top-level workspace_id is null for the default workspace,
                            // which is also how the cost report labels it.
                            defaultWorkspace = key.workspaceId().isEmpty();
                            out.put("workspaceName", defaultWorkspace ? "Default" : client.beta().organization().workspaces().retrieve(workspaceId).name());
                        }
                        break;
                    }
                }
            }
        } finally {
            client.close();
        }

        BigDecimal orgCents = BigDecimal.ZERO;
        BigDecimal workspaceCents = BigDecimal.ZERO;
        String startingAt = monthStart.atStartOfDay(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        String endingAt = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        String page = null;
        do {
            String query = "starting_at=" + encode(startingAt) + "&ending_at=" + encode(endingAt) + "&" + encode("group_by[]") + "=workspace_id&limit=31" + (page == null ? "" : "&page=" + encode(page));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/organizations/cost_report?" + query))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", adminKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("User-Agent", "OIE-Claude-Assistant")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(describe(response.statusCode(), response.body()));
            }
            JsonNode body = MAPPER.readTree(response.body());
            for (JsonNode bucket : body.path("data")) {
                for (JsonNode result : bucket.path("results")) {
                    BigDecimal amount = new BigDecimal(result.path("amount").asText("0"));
                    orgCents = orgCents.add(amount);
                    JsonNode ws = result.get("workspace_id");
                    boolean isDefault = ws == null || ws.isNull();
                    if (workspaceId != null && (defaultWorkspace ? isDefault : !isDefault && workspaceId.equals(ws.asText()))) {
                        workspaceCents = workspaceCents.add(amount);
                    }
                }
            }
            page = body.path("has_more").asBoolean(false) ? body.path("next_page").asText(null) : null;
        } while (page != null);

        // Amounts are cents as decimal strings; report dollars.
        out.put("organizationUsd", orgCents.movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString());
        if (workspaceId != null) {
            out.put("workspaceUsd", workspaceCents.movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return out;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A hint looks like "sk-ant-api03-R2D...igAA": the key must start and end with the visible parts. */
    static boolean matches(String hint, String key) {
        if (hint == null) {
            return false;
        }
        int dots = hint.indexOf("...");
        if (dots < 0) {
            return hint.equals(key);
        }
        String prefix = hint.substring(0, dots);
        String suffix = hint.substring(dots + 3);
        return key.length() >= prefix.length() + suffix.length() && key.startsWith(prefix) && key.endsWith(suffix);
    }

    private static String describe(int status, String body) {
        String message = body;
        try {
            message = MAPPER.readTree(body).path("error").path("message").asText(body);
        } catch (Exception ignored) {
            // not JSON; keep the raw body
        }
        if (status == 401) {
            return "The Admin API key is invalid or revoked.";
        }
        if (status == 403) {
            return "The Admin API key has no access to cost reports (individual accounts have no Admin API): " + message;
        }
        return "Cost report failed (HTTP " + status + "): " + message;
    }
}
