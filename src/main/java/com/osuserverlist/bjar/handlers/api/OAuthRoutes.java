package com.osuserverlist.bjar.handlers.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * OAuth2 token endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/oauth/token} — {@code password} and {@code refresh_token} grants</li>
 *   <li>{@code POST /api/v1/oauth/revoke} — revoke a token (logout)</li>
 *   <li>{@code GET /api/v1/oauth/userinfo} — the identity behind the current access token</li>
 * </ul>
 *
 * <p>Parameters are accepted either as form fields, the way the OAuth2 spec describes, or as a
 * JSON body, because everything else in this API speaks JSON. Tokens come back in the response
 * body and, for browser clients, as HttpOnly cookies; the refresh cookie is scoped to
 * {@code /api/v1/oauth} so it never travels with ordinary requests.</p>
 */
public final class OAuthRoutes {

    private static final Logger logger = LoggerFactory.getLogger("OAuth");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OAuthRoutes() {
    }

    /** POST /api/v1/oauth/token */
    @Host("api.")
    @Path("/api/v1/oauth/token")
    @HttpMethod("POST")
    public static class TokenHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Issue or refresh tokens",
            description = "OAuth2 token endpoint. Supports the password and refresh_token grants and accepts a form encoded or JSON body. The pair is also set as the bjar_access and bjar_refresh cookies. Refresh tokens rotate: reusing one revokes the whole chain.",
            tags = { "OAuth" },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.TokenRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.TokenResponse.class) }, description = "A new access and refresh token pair"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) }, description = "invalid_request or unsupported_grant_type"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) }, description = "invalid_grant: wrong credentials, or an invalid, expired or already used refresh token"),
                @OpenApiResponse(status = "503", content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) }, description = "temporarily_unavailable: the session store is unreachable")
            },
            path = "/api/v1/oauth/token",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            Params params = Params.of(ctx);
            String grantType = params.get("grant_type");

            if (grantType == null) {
                oauthError(ctx, 400, "invalid_request", "grant_type is required.");
                return;
            }

            switch (grantType) {
                case "password" -> password(ctx, params);
                case "refresh_token" -> refresh(ctx, params);
                default -> oauthError(ctx, 400, "unsupported_grant_type",
                        "Only the password and refresh_token grants are supported.");
            }
        }

        /** Resource owner password credentials grant: username and password for a token pair. */
        private void password(Context ctx, Params params) {
            String username = params.get("username");
            String password = params.get("password");
            String passwordMd5 = params.get("password_md5");

            if (username == null || (password == null && passwordMd5 == null)) {
                oauthError(ctx, 400, "invalid_request", "username and password are required.");
                return;
            }

            if (passwordMd5 == null) {
                passwordMd5 = md5Hex(password);
            }

            UserEntity user = UserRepository.findByName(username.trim());

            if (user == null || user.getPasswordHash() == null || !checkPassword(user, passwordMd5)) {
                // Deliberately identical for unknown users and wrong passwords.
                logger.warn("Rejected a token request for <{}> from <{}>", username, ctx.ip());
                oauthError(ctx, 401, "invalid_grant", "Invalid credentials.");
                return;
            }

            String scope = params.get("scope");
            String clientId = params.get("client_id");

            TokenStore.TokenPair pair = TokenStore.issue(user.getId(), user.getName(),
                    user.getPrivileges(), scope, clientId, ctx.ip());

            if (pair == null) {
                oauthError(ctx, 503, "temporarily_unavailable", "Could not issue a token.");
                return;
            }

            logger.info("Issued a token pair to user <{}> from <{}> (scope: {})",
                    user.getId(), ctx.ip(), pair.getScope());

            respond(ctx, pair);
        }

        /** Refresh grant: rotate the pair, invalidating the refresh token that was presented. */
        private void refresh(Context ctx, Params params) {
            String presented = params.get("refresh_token");

            if (presented == null) {
                presented = ctx.cookie(TokenStore.REFRESH_COOKIE);
            }

            if (presented == null || presented.isBlank()) {
                oauthError(ctx, 400, "invalid_request", "refresh_token is required.");
                return;
            }

            TokenStore.RefreshResult result = TokenStore.refresh(presented, ctx.ip());

            if (!result.isSuccess()) {
                switch (result.getError()) {
                    case REPLAYED -> {
                        // The whole family is gone; make the client log in again.
                        clearCookies(ctx);
                        oauthError(ctx, 401, "invalid_grant",
                                "This refresh token has already been used. All sessions in the chain were revoked.");
                    }
                    case UNAVAILABLE -> oauthError(ctx, 503, "temporarily_unavailable",
                            "Could not refresh the token.");
                    default -> {
                        clearCookies(ctx);
                        oauthError(ctx, 401, "invalid_grant", "The refresh token is invalid or expired.");
                    }
                }

                return;
            }

            respond(ctx, result.getPair());
        }

        private void respond(Context ctx, TokenStore.TokenPair pair) {
            ctx.header("Cache-Control", "no-store");
            ctx.header("Pragma", "no-cache");

            // Two Set-Cookie headers; Javalin keeps both when they are added separately.
            ctx.res().addHeader("Set-Cookie", TokenStore.buildAccessCookie(pair.getAccessToken()));
            ctx.res().addHeader("Set-Cookie", TokenStore.buildRefreshCookie(pair.getRefreshToken()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", pair.getAccessToken());
            body.put("token_type", "Bearer");
            body.put("expires_in", pair.getAccessExpiresIn());
            body.put("refresh_token", pair.getRefreshToken());
            body.put("refresh_expires_in", pair.getRefreshExpiresIn());
            body.put("scope", pair.getScope());

            ctx.json(body);
        }

        private boolean checkPassword(UserEntity user, String passwordMd5) {
            try {
                return OpenBSDBCrypt.checkPassword(user.getPasswordHash(), passwordMd5.toCharArray());
            } catch (Exception e) {
                return false;
            }
        }

        private String md5Hex(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));

                StringBuilder builder = new StringBuilder(hashed.length * 2);
                for (byte b : hashed) {
                    builder.append(String.format("%02x", b));
                }

                return builder.toString();
            } catch (Exception e) {
                throw new IllegalStateException("MD5 is unavailable", e);
            }
        }
    }

    /** POST /api/v1/oauth/revoke — RFC 7009 style revocation, used as logout. */
    @Host("api.")
    @Path("/api/v1/oauth/revoke")
    @HttpMethod("POST")
    public static class RevokeHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Revoke tokens (log out)",
            description = "Revokes the given token, or, when no token is supplied, whatever the cookies carry. Revoking a refresh token takes the whole chain down. Both cookies are cleared. As in RFC 7009 the answer is always 200.",
            tags = { "OAuth" },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RevokeRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Revoked, even when the token was already unknown")
            },
            path = "/api/v1/oauth/revoke",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            Params params = Params.of(ctx);

            String token = params.get("token");
            String hint = params.get("token_type_hint");

            if (token == null) {
                // No explicit token: revoke whatever the cookies carry.
                String refreshCookie = ctx.cookie(TokenStore.REFRESH_COOKIE);
                String accessCookie = ctx.cookie(TokenStore.ACCESS_COOKIE);

                if (refreshCookie != null) {
                    TokenStore.revokeRefresh(refreshCookie);
                }

                if (accessCookie != null) {
                    TokenStore.revokeAccess(accessCookie);
                }
            } else if ("access_token".equals(hint)) {
                TokenStore.revokeAccess(token);
            } else {
                // Refresh by default: it takes the whole chain down with it.
                TokenStore.revokeRefresh(token);
                TokenStore.revokeAccess(token);
            }

            clearCookies(ctx);

            // RFC 7009: revocation always answers 200, even for unknown tokens.
            ctx.json(ApiAuth.success());
        }
    }

    /** GET /api/v1/oauth/userinfo — who the current access token belongs to. */
    @Host("api.")
    @Path("/api/v1/oauth/userinfo")
    @HttpMethod("GET")
    public static class UserInfoHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Token owner",
            description = "Who the current access token belongs to, plus its scope, client and expiry.",
            tags = { "OAuth" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.UserInfoResponse.class) }, description = "The token owner"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token")
            },
            path = "/api/v1/oauth/userinfo",
            methods = io.javalin.openapi.HttpMethod.GET
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken token = ApiAuth.require(ctx);

            if (token == null) {
                return;
            }

            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", token.getUserId());
            user.put("name", token.getUsername());
            user.put("priv", token.getPrivileges());

            Map<String, Object> body = ApiAuth.success();
            body.put("user", user);
            body.put("scope", token.getScope());
            body.put("client_id", token.getClientId());
            body.put("expires_at", token.getExpiresAt());

            ctx.json(body);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void clearCookies(Context ctx) {
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredAccessCookie());
        ctx.res().addHeader("Set-Cookie", TokenStore.buildExpiredRefreshCookie());
    }

    /** Writes an RFC 6749 error body. */
    private static void oauthError(Context ctx, int status, String error, String description) {
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
    private static final class Params {
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
