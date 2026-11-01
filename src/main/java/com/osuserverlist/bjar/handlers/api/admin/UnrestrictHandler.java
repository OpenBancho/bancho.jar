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

@Host({"api.", "server", ""})
@Path("/api/v1/admin/unrestrict")
@WebEngine.HttpMethod("POST")
public final class UnrestrictHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Unrestrict a player",
        description = "Lifts a restriction. Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        requestBody =
            @OpenApiRequestBody(
                required = true,
                content = { @OpenApiContent(from = ApiDto.RestrictRequest.class) }
            ),
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
        path = "/api/v1/admin/unrestrict",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        int userId = ApiAuth.intField(body, "user_id");
        if (userId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        String reason = ApiAuth.stringField(body, "reason");

        if (!AdminActions.unrestrict(session.getUserId(), userId, reason)) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        // The lift always goes through, but a staff member who lifts a restriction the anti-cheat still enforces is told that
        // scores will not be signed, and what to do about it.
        java.util.Map<String, Object> answer = ApiAuth.success();
        com.osuserverlist.bjar.models.database.UserEntity target = com.osuserverlist.bjar.repos.UserRepository.findById(userId);
        String notice = com.osuserverlist.bjar.modules.anticheat.AnticheatClient.liftNotice(userId, target == null ? "this player" : target.getName());
        if (notice != null) {
            answer.put("notice", notice);
        }

        ctx.json(answer);
    }
}
