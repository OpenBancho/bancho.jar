package com.osuserverlist.bjar.handlers.api.admin;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.modules.admin.AdminActions;
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
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

@Host({"api.", "server"})
@Path("/api/v1/admin/wipe")
@WebEngine.HttpMethod("POST")
public final class WipeHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Wipe a player",
        description = "Deletes every score and resets the stats of one game mode. Requires the admin scope and the ADMINISTRATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        requestBody =
            @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.WipeRequest.class) }),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "Done"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid field"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the required scope, or the account lacks the required privilege"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No such user"
            )
        },
        path = "/api/v1/admin/wipe",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        int userId = ApiAuth.intField(body, "user_id");
        int mode = ApiAuth.intField(body, "mode");

        if (userId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        if (mode == Integer.MIN_VALUE || mode < 0 || mode >= AdminActions.MODE_COUNT) {
            ApiAuth.badRequest(ctx, "A mode between 0 and " + (AdminActions.MODE_COUNT - 1) + " is required.");
            return;
        }

        if (userId == session.getUserId()) {
            ApiAuth.badRequest(ctx, "You cannot wipe your own account.");
            return;
        }

        if (!AdminActions.wipe(session.getUserId(), userId, mode)) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        ctx.json(ApiAuth.success());
    }
}
