package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiBeatmapsets;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.ebean.DB;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/search_beatmapsets - the beatmap listing behind the web frontend.
 *
 * <p>Sets are not a table of their own, so the search groups the {@code maps}
 * rows by {@code set_id} and then loads every matching set through
 * {@link ApiBeatmapsets}. Grouping in SQL keeps the paging honest: one row of
 * the result is one set, no matter how many difficulties it has.
 *
 * <p>Everything a caller may influence is either a bound parameter or picked
 * from a fixed list, so no query text ever reaches the statement itself.
 */
@Host("api.")
@Path("/api/v1/search_beatmapsets")
@HttpMethod("GET")
public class SearchBeatmapsetsAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Search beatmap sets",
        description = "Searches the known beatmap sets by artist, title, creator or difficulty name. "
                + "One result is one set and carries all of its difficulties.",
        tags = { "Beatmaps" },
        queryParams = {
            @OpenApiParam(name = "q", type = String.class, description = "Free text, matched against artist, title, creator and difficulty name."),
            @OpenApiParam(name = "status", type = Integer.class, description = "Ranked status filter (-2 graveyard, -1 WIP, 0 pending, 1 ranked, 2 approved, 3 qualified, 4 loved)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode filter (0 osu!, 1 taiko, 2 catch, 3 mania)."),
            @OpenApiParam(name = "server", type = String.class, description = "local for sets hosted on this server, osu for mirrored sets. Omit for both."),
            @OpenApiParam(name = "sort", type = String.class, description = "updated (default), plays, difficulty or title."),
            @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset into the result set (default 0)."),
            @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum results to return, 1-100 (default 50).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PaginatedBeatmapsetSearch.class) }, description = "Paginated list of beatmap sets")
        },
        path = "/api/v1/search_beatmapsets",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        String query = trimmed(ctx.queryParam("q"));
        int status = ApiPagination.intParam(ctx, "status", Integer.MIN_VALUE);
        int mode = ApiPagination.intParam(ctx, "mode", Integer.MIN_VALUE);
        String server = server(trimmed(ctx.queryParam("server")));
        String order = order(trimmed(ctx.queryParam("sort")));

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        if (!query.isEmpty()) {
            where.append(" AND (`artist` LIKE :q OR `title` LIKE :q"
                    + " OR `creator` LIKE :q OR `version` LIKE :q)");
        }

        if (status != Integer.MIN_VALUE) {
            where.append(" AND `status` = :status");
        }

        if (mode != Integer.MIN_VALUE) {
            where.append(" AND `mode` = :mode");
        }

        if (server != null) {
            where.append(" AND `server` = :server");
        }

        SqlQuery countQuery = DB.sqlQuery(
                "SELECT COUNT(DISTINCT `set_id`) AS total FROM `maps`" + where);

        SqlQuery pageQuery = DB.sqlQuery(
                "SELECT `set_id`,"
                + " MAX(`last_update`) AS updated,"
                + " SUM(`plays`) AS play_sum,"
                + " MAX(`diff`) AS diff_max,"
                + " MIN(`title`) AS title_min"
                + " FROM `maps`" + where
                + " GROUP BY `set_id`"
                + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset");

        bind(countQuery, query, status, mode, server);
        bind(pageQuery, query, status, mode, server);

        pageQuery.setParameter("limit", limit);
        pageQuery.setParameter("offset", offset);

        Integer total = countQuery.findOne().getInteger("total");

        List<Map<String, Object>> results = new ArrayList<>();

        for (SqlRow row : pageQuery.findList()) {
            Long setId = row.getLong("set_id");

            if (setId == null) {
                continue;
            }

            Map<String, Object> summary = ApiBeatmapsets.summary(setId);

            if (summary != null) {
                results.add(summary);
            }
        }

        ctx.json(ApiPagination.envelope(offset, limit,
                total == null ? results.size() : total, results));
    }

    private static void bind(SqlQuery sql, String query, int status, int mode, String server) {
        if (!query.isEmpty()) {
            sql.setParameter("q", "%" + query + "%");
        }

        if (status != Integer.MIN_VALUE) {
            sql.setParameter("status", status);
        }

        if (mode != Integer.MIN_VALUE) {
            sql.setParameter("mode", mode);
        }

        if (server != null) {
            sql.setParameter("server", server);
        }
    }

    /** Sort orders are picked from a fixed list, never built from input. */
    private static String order(String sort) {
        return switch (sort.toLowerCase(Locale.ROOT)) {
            case "plays" -> "play_sum DESC";
            case "difficulty", "diff", "stars" -> "diff_max DESC";
            case "title" -> "title_min ASC";
            default -> "updated DESC";
        };
    }

    /** Maps the public filter onto the value the maps table actually stores. */
    private static String server(String server) {
        return switch (server.toLowerCase(Locale.ROOT)) {
            case "local", "private", "hosted", "bss" -> "private";
            case "osu", "osu!", "official", "mirror" -> "osu!";
            default -> null;
        };
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
