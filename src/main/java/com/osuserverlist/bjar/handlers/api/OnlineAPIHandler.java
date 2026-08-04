package com.osuserverlist.bjar.handlers.api;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.api.ApiVisibility;
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
 * GET /api/v1/online — the list of players currently online (bots and auxiliary
 * tournament-client sessions excluded, deduplicated by user id).
 */
@Host({"api.", "server", ""})
@Path("/api/v1/online")
@HttpMethod("GET")
public class OnlineAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "List online players",
        description = "Players currently online (bots and auxiliary tournament sessions excluded, deduplicated by id).",
        tags = { "Server" },
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
                content = { @OpenApiContent(from = ApiDto.PaginatedOnline.class) },
                description = "Paginated list of online players"
            )
        },
        path = "/api/v1/online"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        Set<Integer> seen = new HashSet<>();
        List<Map<String, Object>> all = App.server.playerManager.getAllSessions().stream()
                // Bots, tournament client sessions and restricted accounts: a
                // restricted player is online, but not to anybody else.
                .filter(ApiVisibility::isPublic)
                .filter(player -> seen.add(player.getId()))
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", player.getId());
                    row.put("name", player.getUsername());
                    return row;
                })
                .collect(Collectors.toList());

        long count = all.size();
        List<Map<String, Object>> page = all.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        ctx.json(ApiPagination.envelope(offset, limit, count, page));
    }
}
