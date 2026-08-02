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
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_recent_players — the accounts that registered most recently.
 *
 * <p>What the front page needs for its "new players" panel. Only public
 * accounts are returned ({@code priv &amp; 3 = 3}), so a restricted or an
 * unverified registration never shows up in the list.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_recent_players")
@HttpMethod("GET")
public class RecentPlayersAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Recently registered players",
        description = "Public accounts ordered by their registration time, newest first.",
        tags = { "Users" },
        queryParams = {
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
                description = "Paginated list of the newest players"
            )
        },
        path = "/api/v1/get_recent_players"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        PagedList<UserEntity> paged = DB.find(UserEntity.class)
                .where()
                .raw("priv & 3 = 3")
                // The bot account is a fixture of every bancho.jar install and
                // is not somebody who just joined.
                .ne("id", 1)
                .orderBy("creationTime desc, id desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = paged.getList().stream()
                .map(user -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", user.getId());
                    row.put("name", user.getName());
                    row.put("country", user.getCountry());
                    row.put("creation_time", user.getCreationTime());
                    return row;
                })
                .collect(Collectors.toList());

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
