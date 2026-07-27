package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_map_info — metadata for a single beatmap. Scalar endpoint.
 */
@Host({"api.", "server"})
@Path("/api/v1/get_map_info")
@HttpMethod("GET")
public class MapInfoAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Map info",
        description = "Metadata for a single beatmap.",
        tags = { "Maps" },
        queryParams = {
            @OpenApiParam(name = "md5", type = String.class, description = "Beatmap md5 (md5 or id required)."),
            @OpenApiParam(name = "id", type = Integer.class, description = "Beatmap id (md5 or id required).")
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.MapInfoResponse.class) },
                description = "Beatmap metadata"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Beatmap not found"
            )
        },
        path = "/api/v1/get_map_info"
    )
    public void handle(@NotNull Context ctx) {
        BeatmapEntity beatmap = null;

        String md5 = ctx.queryParam("md5");
        if (md5 != null && !md5.isBlank()) {
            beatmap = BeatmapRepository.findByMd5(md5.trim());
        } else {
            String idRaw = ctx.queryParam("id");
            if (idRaw != null && !idRaw.isBlank()) {
                try {
                    beatmap = BeatmapRepository.findById(Long.parseLong(idRaw.trim()));
                } catch (NumberFormatException ignored) {
                    // fall through to the not-found handling below
                }
            }
        }

        if (beatmap == null) {
            ctx.status(404).json(ApiPagination.error("Beatmap not found."));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("map", ApiMappers.beatmap(beatmap));
        ctx.json(body);
    }
}
