package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.ebean.DB;
import io.ebean.SqlRow;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_player_first_places - the maps where this player holds the
 * top score.
 *
 * <p>There is no table of first places, so it is asked of the scores table: a
 * personal best (status 2) is a first place when no unrestricted player has a
 * better personal best on the same map. Ties go to the older score, the same
 * way the in-game leaderboard resolves them.
 */
@Host("api.")
@Path("/api/v1/get_player_first_places")
@HttpMethod("GET")
public class PlayerFirstPlacesAPIHandler implements Handler {

    /** Shared by the count and the page query so they can never drift apart. */
    private static final String CONDITION =
            " FROM `scores` s"
            + " WHERE s.`userid` = :user AND s.`mode` = :mode AND s.`status` = 2"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM `scores` o"
            + "   JOIN `users` ou ON ou.`id` = o.`userid`"
            + "   WHERE o.`map_md5` = s.`map_md5` AND o.`mode` = s.`mode`"
            + "     AND o.`status` = 2 AND (ou.`priv` & 1) > 0"
            + "     AND (o.`pp` > s.`pp` OR (o.`pp` = s.`pp` AND o.`id` < s.`id`))"
            + " )";

    @Override
    @OpenApi(
        summary = "Player first places",
        description = "The maps on which the player holds the number one score, best first.",
        tags = { "Users", "Scores" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
            @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset into the result set (default 0)."),
            @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum results to return, 1-100 (default 50).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PaginatedPlayerScores.class) }, description = "Paginated list of first places"),
            @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Player not found")
        },
        path = "/api/v1/get_player_first_places",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        int mode = ApiPagination.intParam(ctx, "mode", 0);

        UserEntity user = ApiMappers.resolveUser(ctx);

        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        Integer total = DB.sqlQuery("SELECT COUNT(*) AS total" + CONDITION)
                .setParameter("user", user.getId())
                .setParameter("mode", mode)
                .findOne()
                .getInteger("total");

        List<SqlRow> rows = DB.sqlQuery("SELECT s.`id` AS score_id" + CONDITION
                + " ORDER BY s.`pp` DESC LIMIT :limit OFFSET :offset")
                .setParameter("user", user.getId())
                .setParameter("mode", mode)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .findList();

        List<Long> ids = new ArrayList<>();

        for (SqlRow row : rows) {
            Long id = row.getLong("score_id");

            if (id != null) {
                ids.add(id);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();

        if (!ids.isEmpty()) {
            Map<Long, ScoreEntity> byId = new HashMap<>();

            for (ScoreEntity score : DB.find(ScoreEntity.class).where().idIn(ids).findList()) {
                byId.put(score.getId(), score);
            }

            // The SQL order is the one the page should show, so walk the ids.
            for (Long id : ids) {
                ScoreEntity score = byId.get(id);

                if (score != null) {
                    results.add(ApiMappers.score(score, true));
                }
            }
        }

        ctx.json(ApiPagination.envelope(offset, limit,
                total == null ? results.size() : total, results));
    }
}
