package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

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
 * GET /api/v1/get_top_scores — the best scores on the server by pp.
 *
 * <p>Only submitted personal bests ({@code status = 2}) of public accounts are
 * considered, so the list cannot be filled by a restricted player or by the
 * older copies of a play someone has since improved.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_top_scores")
@HttpMethod("GET")
public class TopScoresAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Best scores",
        description = "The highest pp scores on the server, newest personal bests only.",
        tags = { "Server" },
        queryParams = {
            @OpenApiParam(
                name = "mode",
                type = Integer.class,
                description = "Game mode. Omit for every mode at once."
            ),
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
                content = { @OpenApiContent(from = ApiDto.PaginatedPlayerScores.class) },
                description = "Paginated list of the best scores"
            )
        },
        path = "/api/v1/get_top_scores"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        ExpressionList<ScoreEntity> where = DB.find(ScoreEntity.class)
                .fetch("user", "name, country")
                .where()
                .eq("status", 2)
                .gt("pp", 0)
                // The path is written with the property name, not the column
                // name: Ebean resolves "user.privileges" through the join to
                // the users table and writes the priv column itself.
                .raw("user.privileges & 3 = 3");

        // No mode parameter means "the best plays of the whole server", which
        // is what the front page panel asks for.
        String mode = ctx.queryParam("mode");

        if (mode != null && !mode.isBlank()) {
            where.eq("mode", ApiPagination.intParam(ctx, "mode", 0));
        }

        PagedList<ScoreEntity> paged = where
                .orderBy("pp desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = paged.getList().stream()
                .map(score -> {
                    Map<String, Object> row = ApiMappers.score(score, true);
                    // The panel shows who set the play, so the player travels
                    // with the score instead of costing a request each.
                    row.put("player", ApiMappers.userRef(score.getUser()));
                    return row;
                })
                .collect(Collectors.toList());

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
