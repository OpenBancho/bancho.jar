package com.osuserverlist.bjar.handlers.api.oauth;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.Params;
import static com.osuserverlist.bjar.handlers.api.oauth.OAuthSupport.clearCookies;

@Host({"api.", "server"})
@Path("/api/v1/oauth/revoke")
@WebEngine.HttpMethod("POST")
public final class RevokeHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Revoke tokens (log out)",
        description = "Revokes the given token, or, when no token is supplied, whatever the cookies carry. Revoking a refresh token takes the whole chain down. Both cookies are cleared. As in RFC 7009 the answer is always 200.",
        tags = { "OAuth" },
        requestBody =
            @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RevokeRequest.class) }),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "Revoked, even when the token was already unknown"
            )
        },
        path = "/api/v1/oauth/revoke",
        methods = HttpMethod.POST
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
