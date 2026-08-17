package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.GroupRepository;

import io.ebean.DB;
import io.ebean.ExpressionList;
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

@Host({"api.", "server", ""})
@Path("/api/v1/get_leaderboard")
@HttpMethod("GET")
public class LeaderboardAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Global leaderboard",
        description = "Ranking for a given mode. Each row carries an absolute rank.",
        tags = { "Server" },
        queryParams = {
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
            @OpenApiParam(
                name = "sort",
                type = String.class,
                description = "'pp' (default), 'score', 'acc' or 'plays'."
            ),
            @OpenApiParam(
                name = "country",
                type = String.class,
                description = "Two letter country code. Omitted or 'all' for the global ranking."
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
                content = { @OpenApiContent(from = ApiDto.PaginatedLeaderboard.class) },
                description = "Paginated leaderboard"
            )
        },
        path = "/api/v1/get_leaderboard"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        int mode = ApiPagination.intParam(ctx, "mode", 0);
        String sort = ctx.queryParam("sort");
        String country = ctx.queryParam("country");

        String orderBy = switch (sort == null ? "" : sort.toLowerCase()) {
            case "score" -> "rankedScore desc";
            case "acc" -> "accuracy desc";
            case "plays" -> "plays desc";
            default -> "pp desc";
        };

        ExpressionList<StatsEntity> query = DB.find(StatsEntity.class)
                .fetch("user", "name, country")
                .where()
                .eq("id.mode", mode)
                .gt("plays", 0)
                // A restriction takes the account off every public ranking. The
                // path is written with the property name: Ebean resolves it
                // through the join to the users table and writes priv itself.
                .raw("user.privileges & 3 = 3");

        // Country codes are stored lower case. An empty value and "all"
        // both mean the global ranking, so a filter can be cleared
        // without dropping the parameter.
        if (country != null && !country.isBlank() && !"all".equalsIgnoreCase(country.trim())) {
            query.eq("user.country", country.trim().toLowerCase());
        }

        PagedList<StatsEntity> paged = query
                .orderBy(orderBy)
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = new ArrayList<>();
        int rank = offset;
        for (StatsEntity stats : paged.getList()) {
            rank++;
            UserEntity user = stats.getUser();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank);
            row.put("id", stats.getId().getId());
            row.put("name", user != null ? user.getName() : null);
            row.put("country", user != null ? user.getCountry() : null);
            row.put("mode", stats.getId().getMode());
            row.put("pp", stats.getPp());
            row.put("rscore", stats.getRankedScore());
            row.put("tscore", stats.getTotalScore());
            row.put("acc", stats.getAccuracy());
            row.put("plays", stats.getPlays());
            row.put("max_combo", stats.getMaxCombo());
            results.add(row);
        }

        // Group badges travel with each row, so the ranking can show them
        // without a follow-up request per player. One query covers the page.
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> row : results) {
            if (row.get("id") instanceof Integer id) {
                ids.add(id);
            }
        }

        Map<Integer, List<GroupEntity>> groupsByUser = GroupRepository.groupsOfUsers(ids);

        for (Map<String, Object> row : results) {
            List<Map<String, Object>> badges = new ArrayList<>();

            if (row.get("id") instanceof Integer id && groupsByUser.containsKey(id)) {
                for (GroupEntity group : groupsByUser.get(id)) {
                    badges.add(GroupRepository.publicGroup(group));
                }
            }

            row.put("groups", badges);
        }

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
