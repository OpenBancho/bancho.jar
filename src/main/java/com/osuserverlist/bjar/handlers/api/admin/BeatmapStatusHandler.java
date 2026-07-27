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
@Path("/api/v1/admin/beatmap/status")
@WebEngine.HttpMethod("POST")
public final class BeatmapStatusHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Set a beatmap status",
        description = "Ranks, unranks or loves a beatmap. Requires the beatmaps scope and the NOMINATOR privilege; moderators do not get this by default.",
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
                content = { @OpenApiContent(from = ApiDto.BeatmapStatusRequest.class) }
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
                description = "No such beatmap"
            )
        },
        path = "/api/v1/admin/beatmap/status",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireNominator(ctx, session)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        int beatmapId = ApiAuth.intField(body, "beatmap_id");
        int status = ApiAuth.intField(body, "status");

        if (beatmapId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric beatmap_id is required.");
            return;
        }

        if (status == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric status is required.");
            return;
        }

        boolean frozen = ApiAuth.booleanField(body, "frozen", true);

        if (!AdminActions.rankBeatmap(session.getUserId(), beatmapId, status, frozen)) {
            ApiAuth.notFound(ctx, "No such beatmap.");
            return;
        }

        ctx.json(ApiAuth.success());
    }
}
