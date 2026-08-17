package com.osuserverlist.bjar.handlers.api.me;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.RelationshipEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
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
 * GET /api/v1/me/friends — the followers page of the account behind the token.
 *
 * <p>Following is one-directional: a {@code friend} row in {@code relationships}
 * means the owner follows the target, and it is also exactly what the game
 * client's add-friend packet writes, so the website and the client act on the
 * same state. When both directions exist the two follow each other.
 *
 * <p>The answer carries the three lists this splits into: {@code mutual} (both
 * follow each other), {@code followers} (they follow the account, not followed
 * back) and {@code following} (the account follows them, not followed back).
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/friends")
@WebEngine.HttpMethod("GET")
public final class FriendsHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Own followers, following and mutual",
        description = "The three lists of the account behind the access token, as compact user refs with an online flag. Requires the identify scope.",
        tags = { "Me" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The three lists: mutual, followers, following"),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the identify scope"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The account no longer exists"
            )
        },
        path = "/api/v1/me/friends",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireScope(ctx, token, ApiAuth.SCOPE_IDENTIFY)) {
            return;
        }

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        // Indexed by id, so the mutual check is a lookup instead of a loop.
        Map<Integer, UserEntity> followingUsers = new LinkedHashMap<>();
        for (RelationshipEntity row : RelationshipRepository.getFriends(user)) {
            UserEntity target = row.getTarget();

            if (target != null) {
                followingUsers.put(target.getId(), target);
            }
        }

        Map<Integer, UserEntity> followerUsers = new LinkedHashMap<>();
        for (RelationshipEntity row : RelationshipRepository.getIncoming(user)) {
            UserEntity owner = row.getOwner();

            if (owner != null) {
                followerUsers.put(owner.getId(), owner);
            }
        }

        List<Map<String, Object>> mutual = new ArrayList<>();
        List<Map<String, Object>> following = new ArrayList<>();
        List<Map<String, Object>> followers = new ArrayList<>();

        for (Map.Entry<Integer, UserEntity> entry : followingUsers.entrySet()) {
            if (followerUsers.containsKey(entry.getKey())) {
                mutual.add(entry(entry.getValue()));
            } else {
                following.add(entry(entry.getValue()));
            }
        }

        for (Map.Entry<Integer, UserEntity> entry : followerUsers.entrySet()) {
            if (!followingUsers.containsKey(entry.getKey())) {
                followers.add(entry(entry.getValue()));
            }
        }

        Comparator<Map<String, Object>> byName = Comparator.comparing(
                entry -> String.valueOf(entry.get("name")).toLowerCase());

        mutual.sort(byName);
        followers.sort(byName);
        following.sort(byName);

        Map<String, Object> body = ApiAuth.success();
        body.put("mutual", mutual);
        body.put("followers", followers);
        body.put("following", following);
        ctx.json(body);
    }

    /** A list row: who it is, and whether they are in the game right now. */
    private static Map<String, Object> entry(UserEntity user) {
        Map<String, Object> map = new LinkedHashMap<>(ApiMappers.userRef(user));

        Player session = App.server.playerManager.getById(user.getId());
        map.put("online", session != null && !session.isBot());

        return map;
    }
}
