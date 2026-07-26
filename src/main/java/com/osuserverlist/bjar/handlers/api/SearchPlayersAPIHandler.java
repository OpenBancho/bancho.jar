package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
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
 * GET /api/v1/search_players — search public players by (partial) name.
 *
 * <p>Only public accounts are returned ({@code priv & 3 = 3}).
 */
@Host("api.")
@Path("/api/v1/search_players")
@HttpMethod("GET")
public class SearchPlayersAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Search players",
        description = "Search public players by a partial name",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "q", type = String.class, description = "Name fragment to search for."),
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
                content = { @OpenApiContent(from = ApiDto.PaginatedSearchPlayers.class) },
                description = "Paginated list of matching players"
            )
        },
        path = "/api/v1/search_players"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        String query = ctx.queryParam("q");

        ExpressionList<UserEntity> where = DB.find(UserEntity.class)
                .where()
                .raw("priv & 3 = 3");

        if (query != null && !query.isBlank()) {
            where.ilike("name", "%" + query.trim() + "%");
        }

        PagedList<UserEntity> paged = where
                .orderBy("id asc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = paged.getList().stream()
                .map(user -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", user.getId());
                    row.put("name", user.getName());
                    row.put("country", user.getCountry());
                    return row;
                })
                .collect(Collectors.toList());

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
