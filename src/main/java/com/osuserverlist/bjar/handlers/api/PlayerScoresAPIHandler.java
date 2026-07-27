package com.osuserverlist.bjar.handlers.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import io.ebean.ExpressionList;
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_player_scores — a player's scores.
 *
 * <p>{@code best} returns only submitted personal-best scores ordered by pp;
 * {@code recent} returns all scores ordered by play time.
 */
@Host({"api.", "server"})
@Path("/api/v1/get_player_scores")
@HttpMethod("GET")
public class PlayerScoresAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Player scores",
        description = "A player's scores. 'best' returns personal bests by pp; 'recent' returns by play time.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "scope", type = String.class, description = "'recent' (default) or 'best'."),
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
                content = { @OpenApiContent(from = ApiDto.PaginatedPlayerScores.class) },
                description = "Paginated list of scores"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Player not found"
            )
        },
        path = "/api/v1/get_player_scores"
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);
        int mode = ApiPagination.intParam(ctx, "mode", 0);
        String scope = ctx.queryParam("scope");
        if (scope == null || scope.isBlank()) {
            scope = "recent";
        }

        UserEntity user = ApiMappers.resolveUser(ctx);
        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        ExpressionList<ScoreEntity> where = DB.find(ScoreEntity.class)
                .where()
                .eq("user.id", user.getId())
                .eq("mode", mode);

        String orderBy;
        if ("best".equalsIgnoreCase(scope)) {
            where.eq("status", 2);
            orderBy = "pp desc";
        } else {
            orderBy = "playTime desc";
        }

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
                .orderBy(orderBy)
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = paged.getList().stream()
                .map(score -> ApiMappers.score(score, true))
                .collect(Collectors.toList());

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }
}
