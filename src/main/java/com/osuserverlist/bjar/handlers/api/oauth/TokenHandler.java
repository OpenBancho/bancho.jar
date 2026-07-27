package com.osuserverlist.bjar.handlers.api.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.Params;
import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.clearCookies;
import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.logger;
import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.oauthError;

@Host({"api.", "server", ""})
@Path("/api/v1/oauth/token")
@WebEngine.HttpMethod("POST")
public final class TokenHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Issue or refresh tokens",
        description = "OAuth2 token endpoint. Supports the password and refresh_token grants and accepts a form encoded or JSON body. The pair is also set as the bjar_access and bjar_refresh cookies. Refresh tokens rotate: reusing one revokes the whole chain.",
        tags = { "OAuth" },
        requestBody =
            @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.TokenRequest.class) }),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.TokenResponse.class) },
                description = "A new access and refresh token pair"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) },
                description = "invalid_request or unsupported_grant_type"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) },
                description = "invalid_grant: wrong credentials, or an invalid, expired or already used refresh token"
            ),
            @OpenApiResponse(
                status = "503",
                content = { @OpenApiContent(from = ApiDto.OAuthErrorResponse.class) },
                description = "temporarily_unavailable: the session store is unreachable"
            )
        },
        path = "/api/v1/oauth/token",
        methods = HttpMethod.POST
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
