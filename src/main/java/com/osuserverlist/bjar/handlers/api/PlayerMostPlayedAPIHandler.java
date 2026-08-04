package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiVisibility;
import com.osuserverlist.bjar.models.api.ApiPagination;
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
 * GET /api/v1/get_player_most_played — a player's most-played beatmaps for a
 * mode. {@code count} is the number of distinct beatmaps played in that mode.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_player_most_played")
@HttpMethod("GET")
public class PlayerMostPlayedAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Most played maps",
        description = "A player's most-played beatmaps for a mode. count = distinct maps played.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
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
                content = { @OpenApiContent(from = ApiDto.PaginatedMostPlayed.class) },
                description = "Paginated list of most-played maps"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Player not found"
            )
        },
        path = "/api/v1/get_player_most_played"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        int mode = ApiPagination.intParam(ctx, "mode", 0);

        UserEntity user = ApiVisibility.resolveVisibleUser(ctx);
        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        SqlRow countRow = DB.sqlQuery(
                "SELECT COUNT(DISTINCT s.map_md5) AS c "
                        + "FROM scores s "
                        + "WHERE s.userid = :uid AND s.mode = :mode")
                .setParameter("uid", user.getId())
                .setParameter("mode", mode)
                .findOne();
        long count = countRow == null || countRow.getLong("c") == null ? 0L : countRow.getLong("c");

        // offset/limit are validated integers, so inlining them here is safe.
        List<SqlRow> rows = DB.sqlQuery(
                "SELECT s.map_md5 AS md5, m.id AS map_id, m.set_id AS set_id, "
                        + "m.artist AS artist, m.title AS title, m.version AS version, "
                        + "COUNT(*) AS playcount "
                        + "FROM scores s "
                        + "INNER JOIN maps m ON s.map_md5 = m.md5 "
                        + "WHERE s.userid = :uid AND s.mode = :mode "
                        + "GROUP BY s.map_md5, m.id, m.set_id, m.artist, m.title, m.version "
                        + "ORDER BY playcount DESC "
                        + "LIMIT " + limit + " OFFSET " + offset)
                .setParameter("uid", user.getId())
                .setParameter("mode", mode)
                .findList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (SqlRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("map_md5", row.getString("md5"));
            item.put("map_id", row.getLong("map_id"));
            item.put("set_id", row.getLong("set_id"));
            item.put("artist", row.getString("artist"));
            item.put("title", row.getString("title"));
            item.put("version", row.getString("version"));
            item.put("playcount", row.getLong("playcount"));
            results.add(item);
        }

        ctx.json(ApiPagination.envelope(offset, limit, count, results));
    }
}
