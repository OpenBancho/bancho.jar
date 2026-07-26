package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiBeatmapsets;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_beatmapset - one beatmap set with every difficulty it has.
 *
 * <p>This is what a beatmap page needs in a single request; it works the same
 * for mirrored osu! sets and for sets uploaded here through the submission
 * system.
 */
@Host("api.")
@Path("/api/v1/get_beatmapset")
@HttpMethod("GET")
public class BeatmapsetInfoAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Beatmap set",
        description = "One beatmap set with all of its difficulties.",
        tags = { "Beatmaps" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Beatmap set id (required).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.BeatmapsetResponse.class) }, description = "The beatmap set"),
            @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or malformed id"),
            @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Beatmap set not found")
        },
        path = "/api/v1/get_beatmapset",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        String raw = ctx.queryParam("id");

        if (raw == null || raw.isBlank()) {
            ctx.status(400).json(ApiPagination.error("A beatmap set id is required."));
            return;
        }

        long setId;

        try {
            setId = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            ctx.status(400).json(ApiPagination.error("The beatmap set id has to be a number."));
            return;
        }

        Map<String, Object> beatmapset = ApiBeatmapsets.summary(setId);

        if (beatmapset == null) {
            ctx.status(404).json(ApiPagination.error("Beatmap set not found."));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("beatmapset", beatmapset);

        ctx.json(body);
    }
}
