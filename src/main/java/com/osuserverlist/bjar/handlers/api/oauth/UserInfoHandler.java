package com.osuserverlist.bjar.handlers.api.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

@Host({"api.", "server", ""})
@Path("/api/v1/oauth/userinfo")
@WebEngine.HttpMethod("GET")
public final class UserInfoHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Token owner",
        description = "Who the current access token belongs to, plus its scope, client and expiry.",
        tags = { "OAuth" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.UserInfoResponse.class) },
                description = "The token owner"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            )
        },
        path = "/api/v1/oauth/userinfo",
        methods = HttpMethod.GET
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
