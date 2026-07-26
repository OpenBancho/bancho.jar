package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.AchievementEntity;
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
 * GET /api/v1/get_player_achievements - the medals of one player.
 *
 * <p>The whole catalogue is returned, every entry carrying an {@code unlocked}
 * flag, so a client can show the medals a player is missing without asking a
 * second time. The catalogue is small (the seed holds well under a hundred
 * rows), which is why this endpoint is not paginated.
 *
 * <p>Medals are not per mode: a single row in {@code user_achievements} means
 * the medal is owned, no matter which mode earned it. There is no unlock
 * timestamp in that table, so no date can be reported.
 *
 * <p>The icon of a medal is {@code file} plus an extension, served from the
 * assets host: {@code https://assets.<domain>/medals/client/<file>@2x.png}.
 */
@Host("api.")
@Path("/api/v1/get_player_achievements")
@HttpMethod("GET")
public class PlayerAchievementsAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Player achievements",
        description = "Every medal of the server with a flag telling whether this player owns it.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "unlocked_only", type = Boolean.class, description = "Return only the medals the player owns (default false).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.AchievementsResponse.class) }, description = "The medals of the player"),
            @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Player not found")
        },
        path = "/api/v1/get_player_achievements",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        UserEntity user = ApiMappers.resolveUser(ctx);

        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        boolean unlockedOnly = "1".equals(ctx.queryParam("unlocked_only"))
                || "true".equalsIgnoreCase(ctx.queryParam("unlocked_only"));

        // Read as plain sql rather than through the association, so the user
        // and the achievement behind every row are not lazily loaded one by
        // one just to learn their ids.
        Set<Integer> owned = new HashSet<>();

        for (SqlRow row : DB.sqlQuery(
                "SELECT `achid` AS achid FROM `user_achievements` WHERE `userid` = :user")
                .setParameter("user", user.getId())
                .findList()) {
            Integer id = row.getInteger("achid");

            if (id != null) {
                owned.add(id);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (AchievementEntity achievement : DB.find(AchievementEntity.class)
                .orderBy("id")
                .findList()) {
            boolean unlocked = owned.contains(achievement.getId());

            if (unlockedOnly && !unlocked) {
                continue;
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", achievement.getId());
            map.put("file", achievement.getFile());
            map.put("name", achievement.getName());
            map.put("description", achievement.getDescription());
            map.put("unlocked", unlocked);
            // The condition is deliberately left out: it is the rule the
            // server checks, and publishing it only helps someone game it.
            results.add(map);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        // count is what the player owns, total is what exists, so a client can
        // render "12 / 83" without counting the list itself.
        body.put("count", owned.size());
        body.put("total", DB.find(AchievementEntity.class).findCount());
        body.put("results", results);

        ctx.json(body);
    }
}
