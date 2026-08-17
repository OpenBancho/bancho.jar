package com.osuserverlist.bjar.handlers.api.me;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiVisibility;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.RelationshipRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/me/friends/status — how the account behind the token stands with
 * one other player: nothing, following, followed by, or mutual.
 *
 * <p>This is what draws the button on a profile page, so it also says whether
 * a block sits between the two accounts, which is when no button should be
 * shown at all.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/friends/status")
@WebEngine.HttpMethod("GET")
public final class FriendStatusHandler implements Handler {

    /** The states the website renders, as a small vocabulary of its own. */
    public static String relationship(UserEntity user, UserEntity target) {
        boolean following = RelationshipRepository.isFriend(user, target);
        boolean followedBy = RelationshipRepository.isFriend(target, user);

        if (following && followedBy) {
            return "mutual";
        }
        if (following) {
            return "following";
        }
        if (followedBy) {
            return "followed_by";
        }

        return "none";
    }

    @Override
    @OpenApi(
        summary = "Relation with one player",
        description = "none, following, followed_by or mutual - between the account behind the access token and the player named by id. Requires the identify scope.",
        tags = { "Me" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "The other player's id.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The relationship state and the block flags"),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or malformed id"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No such player"
            )
        },
        path = "/api/v1/me/friends/status",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireScope(ctx, token, ApiAuth.SCOPE_IDENTIFY)) {
            return;
        }

        int targetId;
        try {
            targetId = Integer.parseInt(String.valueOf(ctx.queryParam("id")).trim());
        } catch (NumberFormatException e) {
            ApiAuth.badRequest(ctx, "A numeric id is required.");
            return;
        }

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        UserEntity target = UserRepository.findById(targetId);
        // A restricted account is answered exactly like one that never
        // existed, the way its profile page is.
        if (target == null || !ApiVisibility.isPublic(target)) {
            ApiAuth.notFound(ctx, "Player not found.");
            return;
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("relationship", relationship(user, target));
        body.put("blocked", RelationshipRepository.isBlocked(user, target));
        body.put("blocked_by", RelationshipRepository.isBlocked(target, user));
        ctx.json(body);
    }
}
