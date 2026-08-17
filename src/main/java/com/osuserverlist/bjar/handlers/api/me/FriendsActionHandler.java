package com.osuserverlist.bjar.handlers.api.me;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
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
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * POST /api/v1/me/friends — changing who the account behind the token follows.
 * Which change a call means is in the {@code action} field:
 *
 * <ul>
 *   <li>{@code follow} follows the player - instant, no request is needed;</li>
 *   <li>{@code unfollow} stops following: only the caller's own row goes away,
 *       the other player keeps following if they did;</li>
 *   <li>{@code remove_follower} takes the other player off the caller's
 *       followers.</li>
 * </ul>
 *
 * <p>A follow is a one-directional {@code friend} row in {@code relationships} -
 * the same row the game client's add-friend packet writes, so following on the
 * website and in game are one state. When both directions exist, the two are
 * mutual followers.
 *
 * <p>The friend-request verbs this endpoint started with keep working as
 * aliases, so an older caller never breaks: add/accept are follow, cancel/remove
 * are unfollow, decline is remove_follower.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/friends")
@WebEngine.HttpMethod("POST")
public final class FriendsActionHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Follow or unfollow",
        description = "Follows, unfollows or removes a follower, chosen by the action field. Requires the profile scope and an unrestricted account.",
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
                content = { @OpenApiContent(from = ApiDto.FriendActionRequest.class) }
            ),
        responses = {
            @OpenApiResponse(status = "200", description = "Done; the new relationship state is in the answer"),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing field, unknown action, or a state the action does not apply to"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the profile scope, the account is restricted, or a block sits between the two players"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No such player"
            )
        },
        path = "/api/v1/me/friends",
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

        int targetId = ApiAuth.intField(body, "id");
        if (targetId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "An id is required.");
            return;
        }

        String action = ApiAuth.stringField(body, "action");
        if (action == null) {
            ApiAuth.badRequest(ctx, "An action is required: follow, unfollow or remove_follower.");
            return;
        }

        // The friend-request vocabulary, mapped onto the follower verbs so
        // callers written against it keep working.
        action = switch (action) {
            case "add", "accept" -> "follow";
            case "cancel", "remove" -> "unfollow";
            case "decline" -> "remove_follower";
            default -> action;
        };

        if (targetId == token.getUserId()) {
            ApiAuth.badRequest(ctx, "You cannot follow yourself.");
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

        boolean following = RelationshipRepository.isFriend(user, target);
        boolean followedBy = RelationshipRepository.isFriend(target, user);

        switch (action) {
            case "follow" -> {
                if (following && followedBy) {
                    ApiAuth.badRequest(ctx, "You already follow each other.");
                    return;
                }
                if (following) {
                    ApiAuth.badRequest(ctx, "You already follow this player.");
                    return;
                }
                if (!noBlockBetween(ctx, user, target)) {
                    return;
                }

                RelationshipRepository.addFriend(user, target);

                // Following someone who follows you makes the two of you mutual.
                answer(ctx, followedBy ? "mutual" : "following");
            }
            case "unfollow" -> {
                if (!following) {
                    ApiAuth.badRequest(ctx, "You do not follow this player.");
                    return;
                }

                // Only the caller's own row goes away: whether the other player
                // keeps following is their choice, not this button's.
                RelationshipRepository.removeFriend(user, target);
                answer(ctx, followedBy ? "followed_by" : "none");
            }
            case "remove_follower" -> {
                if (!followedBy) {
                    ApiAuth.badRequest(ctx, "This player does not follow you.");
                    return;
                }

                RelationshipRepository.removeFriend(target, user);
                answer(ctx, following ? "following" : "none");
            }
            default -> ApiAuth.badRequest(ctx, "Unknown action: " + action + ".");
        }
    }

    /**
     * A block in either direction stops new follows from being written. It never
     * stops removals: unfollowing or shedding a follower must always be
     * possible.
     */
    private static boolean noBlockBetween(Context ctx, UserEntity user, UserEntity target) {
        if (RelationshipRepository.isBlocked(user, target)) {
            ApiAuth.badRequest(ctx,
                    "You have blocked this player. Unblock them in the game client first.");
            return false;
        }

        if (RelationshipRepository.isBlocked(target, user)) {
            ctx.status(403).json(ApiPagination.error(
                    "This player cannot be followed."));
            return false;
        }

        return true;
    }

    /** Every successful answer carries the state the caller should show next. */
    private static void answer(Context ctx, String relationship) {
        Map<String, Object> body = ApiAuth.success();
        body.put("relationship", relationship);
        ctx.json(body);
    }
}
