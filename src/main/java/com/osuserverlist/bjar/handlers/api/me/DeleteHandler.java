package com.osuserverlist.bjar.handlers.api.me;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.RelationshipEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserAchievementEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.ebean.DB;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.LEADERBOARD_KEY;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.clearCookies;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.confirmPassword;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.sessionsOf;

@Host("api.")
@Path("/api/v1/me/delete")
@WebEngine.HttpMethod("POST")
public final class DeleteHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Delete own account",
        description = "Irreversibly deletes the account behind the access token together with its scores, stats and sessions. The current password has to be supplied again. Requires the profile scope.",
        tags = { "Me" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        requestBody =
            @OpenApiRequestBody(
                required = true,
                content = { @OpenApiContent(from = ApiDto.SelfDeleteRequest.class) }
            ),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "Done"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid field"
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
            )
        },
        path = "/api/v1/me/delete",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        UserEntity user = confirmPassword(ctx, token, body);
        if (user == null) {
            return;
        }

        // Deliberately awkward: this cannot be undone.
        if (!ApiAuth.booleanField(body, "confirm", false)) {
            ApiAuth.badRequest(ctx, "Set confirm to true to delete the account.");
            return;
        }

        int userId = user.getId();

        List<ScoreEntity> scores = DB.find(ScoreEntity.class)
                .where()
                .eq("user.id", userId)
                .findList();

        if (!scores.isEmpty()) {
            DB.deleteAll(scores);
        }

        for (StatsEntity stats : StatsRepository.findAllByUser(userId)) {
            StatsRepository.delete(stats);
        }

        List<RelationshipEntity> owned = DB.find(RelationshipEntity.class)
                .where()
                .eq("owner.id", userId)
                .findList();

        if (!owned.isEmpty()) {
            DB.deleteAll(owned);
        }

        // Also the rows where somebody else befriended or blocked this account.
        List<RelationshipEntity> incoming = DB.find(RelationshipEntity.class)
                .where()
                .eq("target.id", userId)
                .findList();

        if (!incoming.isEmpty()) {
            DB.deleteAll(incoming);
        }

        List<UserAchievementEntity> achievements = DB.find(UserAchievementEntity.class)
                .where()
                .eq("user.id", userId)
                .findList();

        if (!achievements.isEmpty()) {
            DB.deleteAll(achievements);
        }

        for (int mode = 0; mode < AdminActions.MODE_COUNT; mode++) {
            Redis.getClient().zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));
        }

        for (Player player : sessionsOf(userId)) {
            player.sendPacket(new NotificationPacket("Your account has been deleted."));
            App.server.playerManager.disconnect(player);
        }

        TokenStore.revokeAccess(token.getToken());
        TokenStore.revokeFamily(token.getFamilyId());
        clearCookies(ctx);

        UserRepository.delete(user);

        logger.warn("User <{}> deleted their own account from <{}> ({} score(s) removed)",
                userId, ctx.ip(), scores.size());

        ctx.json(ApiAuth.success());
    }
}
