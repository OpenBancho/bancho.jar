package com.osuserverlist.bjar.handlers.api.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.modules.api.TokenStore;

import io.javalin.http.Context;

/** Shared request parsing and error rendering of the OAuth endpoints. */
final class OAuthSupport {
    static final Logger logger = LoggerFactory.getLogger("OAuth");

    static final ObjectMapper MAPPER = new ObjectMapper();

    static void clearCookies(Context ctx) {
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredAccessCookie());
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredRefreshCookie());
    }

    /** Writes an RFC 6749 error body. */
    static void oauthError(Context ctx, int status, String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);

        ctx.status(status);
        ctx.header("Cache-Control", "no-store");

        if (status == 401) {
            ctx.header("WWW-Authenticate",
                    "Bearer error=\"" + error + "\", error_description=\"" + description + "\"");
        }

        ctx.json(body);
    }

    /**
     * Reads parameters from a form body, as the OAuth2 spec expects, or from a JSON body,
     * as the rest of this API does. Query parameters are accepted as a last resort.
     */
    static final class Params {
        private final Context ctx;
        private final JsonNode json;

        private Params(Context ctx, JsonNode json) {
            this.ctx = ctx;
            this.json = json;
        }

        static Params of(Context ctx) {
            JsonNode parsed = null;
            String type = ctx.header("Content-Type");

            if (type != null && type.toLowerCase().contains("json")) {
                try {
                    JsonNode node = MAPPER.readTree(ctx.body());

                    if (node != null && node.isObject()) {
                        parsed = node;
                    }
                } catch (Exception ignored) {
                    // Fall through; the caller reports the missing parameters instead.
                }
            }

            return new Params(ctx, parsed);
        }

        String get(String name) {
            if (json != null) {
                JsonNode node = json.get(name);

                if (node != null && node.isValueNode()) {
                    String value = node.asText();
                    return value.isBlank() ? null : value;
                }

                return null;
            }

            String value = ctx.formParam(name);

            if (value == null || value.isBlank()) {
                value = ctx.queryParam(name);
            }

            return value == null || value.isBlank() ? null : value;
        }
    }
}
