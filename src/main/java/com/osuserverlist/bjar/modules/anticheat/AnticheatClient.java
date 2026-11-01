package com.osuserverlist.bjar.modules.anticheat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.models.ConfigModels.AnticheatConfiguration;

/**
 * Talks to the OpenWatcher anti-cheat server on behalf of this game server.
 *
 * <p>The anti-cheat owns the restriction of a player it flagged and decides whether a score is signed. A game server can only
 * ask what it knows ({@code status}) and vouch for a player whose restriction is a heuristic one. A confirmed (hard) restriction
 * is never vouchable: the anti-cheat answers {@code 409 hard_restriction} and the owner appeals to OpenWatcher support.</p>
 *
 * <p>Configuration: {@code .config/anticheat.toml} ({@code url}, {@code serverKey}). Empty = the anti-cheat is not used.</p>
 */
public final class AnticheatClient {

    private static final Logger logger = LoggerFactory.getLogger("Anticheat");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static AnticheatConfiguration config;

    /** Status code of the anti-cheat's answer (or 502 / 503 when it could not be reached / is not configured) and its JSON body. */
    public record Result(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String error() {
            return body != null && body.hasNonNull("error") ? body.get("error").asText() : "The anti-cheat server did not answer.";
        }

        public String code() {
            return body != null && body.hasNonNull("code") ? body.get("code").asText() : "";
        }
    }

    private AnticheatClient() {
    }

    private static synchronized AnticheatConfiguration config() {
        if (config == null) {
            config = AnticheatConfiguration.load();
        }
        return config;
    }

    public static boolean isConfigured() {
        AnticheatConfiguration c = config();
        return c.getUrl() != null && !c.getUrl().isBlank() && c.getServerKey() != null && !c.getServerKey().isBlank();
    }

    public static Result status(int userId) {
        return send("GET", "/v1/server/status?user_id=" + userId, null);
    }

    public static Result vouches() {
        return send("GET", "/v1/server/vouches?limit=200", null);
    }

    /**
     * @param agreement    the word the staff member typed after reading the terms of vouching (the anti-cheat decides whether it is the right one)
     * @param termsVersion the version of the terms that was shown (the terms come with {@link #status})
     */
    public static Result vouch(int userId, String reason, String issuedBy, String agreement, int termsVersion) {
        Map<String, Object> body = body(userId, reason, issuedBy);
        body.put("agreement", agreement == null ? "" : agreement);
        body.put("terms_version", termsVersion);
        return send("POST", "/v1/server/vouch", body);
    }

    public static Result revoke(int userId, String reason, String issuedBy) {
        return send("POST", "/v1/server/vouch/revoke", body(userId, reason, issuedBy));
    }

    /**
     * A sentence for the staff member who just lifted a restriction here while the anti-cheat still enforces it, or {@code null}
     * when nothing needs saying (not configured, not restricted by the anti-cheat, or already vouched for). The lift itself is
     * never blocked: the owner keeps control of the server, but is told that scores will not be signed.
     */
    public static String liftNotice(int userId, String playerName) {
        if (!isConfigured()) {
            return null;
        }

        try {
            Result result = status(userId);
            if (!result.ok() || result.body() == null || !result.body().path("restricted").asBoolean(false) || result.body().path("vouched").asBoolean(false)) {
                return null;
            }

            boolean soft = result.body().path("vouchable").asBoolean(false);
            return "Note: OpenWatcher anti-cheat still restricts " + playerName + ". Lifting the restriction here does NOT let the player submit scores. "
                    + (soft
                        ? "The restriction comes from an automatic rule: after you checked the player, you can vouch for them (Anti-cheat > Vouch), or contact OpenWatcher support."
                        : "It is confirmed by OpenWatcher and cannot be vouched for: the player must contact OpenWatcher support.");
        } catch (RuntimeException e) {
            logger.warn("Could not ask the anti-cheat about user {}", userId, e);
            return null;
        }
    }

    private static Map<String, Object> body(int userId, String reason, String issuedBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("reason", reason == null ? "" : reason);
        body.put("issued_by", issuedBy);
        return body;
    }

    private static Result send(String method, String path, Map<String, Object> payload) {
        if (!isConfigured()) {
            return failure(503, "The anti-cheat is not configured on this server (.config/anticheat.toml).");
        }

        AnticheatConfiguration c = config();

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(c.getUrl().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-API-Key", c.getServerKey())
                    .header("Accept", "application/json");

            if (payload == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            }

            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json;

            try {
                json = MAPPER.readTree(response.body());
            } catch (Exception e) {
                json = MAPPER.createObjectNode().put("error", "The anti-cheat server sent an unreadable answer.");
            }

            return new Result(response.statusCode(), json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(502, "The anti-cheat request was interrupted.");
        } catch (Exception e) {
            logger.warn("Anti-cheat request {} {} failed: {}", method, path, e.toString());
            return failure(502, "The anti-cheat server could not be reached.");
        }
    }

    private static Result failure(int status, String message) {
        return new Result(status, MAPPER.createObjectNode().put("error", message));
    }
}
