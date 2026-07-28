package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.LogEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.LogRepository;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/player - one account, its staff history, and its per-mode totals.
 *
 * <p>Answered as a single document on purpose. The moderator's page needs all three parts before
 * it is worth looking at, and three round trips means three chances to render a half-page where
 * the history has not arrived yet and the account therefore looks clean.</p>
 *
 * <p>The history is the point of this endpoint. A restriction on its own is just a state; what
 * makes it reviewable is knowing who applied it, when, and what reason they gave, which is
 * information the server previously only wrote to a log file nobody reads.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/player")
@HttpMethod("GET")
public final class PlayerHandler implements Handler {

    /** How much history the profile page carries. The logs section pages through the rest. */
    private static final int HISTORY_LIMIT = 100;

    @Override
    @OpenApi(
        summary = "Load one player for moderation",
        description = "One account with its staff history and per-mode totals. Requires the moderation "
                + "scope and the MODERATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        queryParams = {
            @OpenApiParam(name = "user_id", type = Integer.class, description = "The account to load.")
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.AdminPlayerResponse.class) },
                description = "The account"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid user_id"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the required scope, or the account lacks the required privilege"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No such user"
            )
        },
        path = "/api/v1/admin/player"
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        Integer userId = userId(ctx);
        if (userId == null) {
            ctx.status(400).json(ApiPagination.error("A numeric user_id is required."));
            return;
        }

        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        Set<Integer> online = AdminPresenter.onlineIds();

        List<LogEntity> history = LogRepository.findByTarget(userId, 0, HISTORY_LIMIT);

        Map<String, Object> response = ApiAuth.success();

        response.put("player", AdminPresenter.player(user, online));
        response.put("email", user.getEmail());
        response.put("logs", AdminPresenter.logs(history));
        response.put("log_count", LogRepository.countByTarget(userId));
        response.put("stats", stats(userId));

        ctx.json(response);
    }

    /** Per-mode totals, modes the player has never touched omitted. */
    private static List<Map<String, Object>> stats(int userId) {
        List<Map<String, Object>> modes = new ArrayList<>();

        for (StatsEntity entry : StatsRepository.findByUser(userId)) {
            if (entry.getPlays() == null || entry.getPlays() == 0) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("mode", entry.getId() == null ? 0 : entry.getId().getMode());
            row.put("pp", entry.getPp());
            row.put("plays", entry.getPlays());
            row.put("playtime", entry.getPlaytime());
            row.put("accuracy", entry.getAccuracy());
            row.put("ranked_score", entry.getRankedScore());
            row.put("total_score", entry.getTotalScore());
            row.put("max_combo", entry.getMaxCombo());
            row.put("total_hits", entry.getTotalHits());
            row.put("replay_views", entry.getReplayViews());

            modes.add(row);
        }

        return modes;
    }

    /** Reads user_id from the query string. */
    private static Integer userId(Context ctx) {
        String raw = ctx.queryParam("user_id");

        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
