package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.api.ApiVisibility;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.ScoreRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_score_info — a single score with its beatmap embedded.
 * Scalar endpoint.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_score_details")
@HttpMethod("GET")
public class ScoreInfoAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Score info",
        description = "A single score with its beatmap embedded.",
        tags = { "Scores" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "Score id.")
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.ScoreInfoResponse.class) },
                description = "Score with beatmap"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid id"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Score not found"
            )
        },
        path = "/api/v1/get_score_details"
    )
    public void handle(@NotNull Context ctx) {
        String idRaw = ctx.queryParam("id");
        if (idRaw == null || idRaw.isBlank()) {
            ctx.status(400).json(ApiPagination.error("Must provide a score id!"));
            return;
        }

        ScoreEntity score;
        try {
            score = ScoreRepository.findById(Long.parseLong(idRaw.trim()));
        } catch (NumberFormatException e) {
            ctx.status(400).json(ApiPagination.error("Invalid score id."));
            return;
        }

        if (score == null) {
            ctx.status(404).json(ApiPagination.error("Score not found."));
            return;
        }

        // A restricted player's plays go with them: the score page would name the
        // account and link to a profile that answers 404, so it answers 404 too.
        if (!ApiVisibility.canView(ctx, score.getUser())) {
            ctx.status(404).json(ApiPagination.error("Score not found."));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("score", ApiMappers.score(score, true));
        ctx.json(body);
    }
}
