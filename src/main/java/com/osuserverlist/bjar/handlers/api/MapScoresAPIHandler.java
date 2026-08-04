package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.ebean.DB;
import io.ebean.ExpressionList;
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_map_scores — the submitted leaderboard scores for a beatmap.
 *
 * <p>Scores are ordered by score descending.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_map_scores")
@HttpMethod("GET")
public class MapScoresAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Map scores",
        description = "Submitted leaderboard scores for a beatmap, ordered by score.",
        tags = { "Scores" },
        queryParams = {
            @OpenApiParam(name = "md5", type = String.class, description = "Beatmap md5 (md5 or id required)."),
            @OpenApiParam(name = "id", type = Integer.class, description = "Beatmap id (md5 or id required)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
            @OpenApiParam(name = "mods", type = Integer.class, description = "Mods bitmask filter."),
            @OpenApiParam(
                name = "offset",
                type = Integer.class,
                description = "Zero-based offset into the result set (default 0)."
            ),
            @OpenApiParam(
                name = "limit",
                type = Integer.class,
                description = "Maximum results to return, 1-100 (default 50)."
            )
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.PaginatedMapScores.class) },
                description = "Paginated list of scores"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing md5/id"
            )
        },
        path = "/api/v1/get_map_scores"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        int mode = ApiPagination.intParam(ctx, "mode", 0);

        String md5 = ctx.queryParam("md5");
        if (md5 == null || md5.isBlank()) {
            String idRaw = ctx.queryParam("id");
            if (idRaw != null && !idRaw.isBlank()) {
                try {
                    BeatmapEntity beatmap = BeatmapRepository.findById(Long.parseLong(idRaw.trim()));
                    if (beatmap != null) {
                        md5 = beatmap.getMd5();
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the missing-parameter error below
                }
            }
        }

        if (md5 == null || md5.isBlank()) {
            ctx.status(400).json(ApiPagination.error("Must provide either md5 OR id!"));
            return;
        }

        ExpressionList<ScoreEntity> where = DB.find(ScoreEntity.class)
                .fetch("user")
                .where()
                .eq("mapMd5", md5)
                .eq("mode", mode)
                .eq("status", 2)
                // A restricted player is off the map's leaderboard as well, so
                // the ranking here matches the one the game is served.
                .raw("user.privileges & 3 = 3");

        String mods = ctx.queryParam("mods");
        if (mods != null && !mods.isBlank()) {
            try {
                int modsValue = Integer.parseInt(mods.trim());
                where.raw("mods & ? = ?", modsValue, modsValue);
            } catch (NumberFormatException ignored) {
                // ignore an unparsable mods filter
            }
        }

        PagedList<ScoreEntity> paged = where
                .orderBy("score desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (ScoreEntity score : paged.getList()) {
            Map<String, Object> row = ApiMappers.score(score, false);
            row.put("player", ApiMappers.userRef(score.getUser()));
            results.add(row);
        }

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
