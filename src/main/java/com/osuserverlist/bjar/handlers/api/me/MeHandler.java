package com.osuserverlist.bjar.handlers.api.me;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

@Host("api.")
@Path("/api/v1/me")
@WebEngine.HttpMethod("GET")
public final class MeHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Own profile",
        description = "The account behind the access token, including the private fields (email, silence and donor end, userpage). Requires the identify scope.",
        tags = { "Me" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SelfResponse.class) },
                description = "Own profile and stats per mode"
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
                description = "The account no longer exists"
            )
        },
        path = "/api/v1/me",
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

        // The public mapper, plus the fields only the owner is allowed to see.
        Map<String, Object> info = new LinkedHashMap<>(ApiMappers.userInfo(user));
        info.put("email", user.getEmail());
        info.put("silence_end", user.getSilenceEnd());
        info.put("donor_end", user.getDonorEnd());
        info.put("clan_priv", user.getClanPriv());
        info.put("userpage_content", user.getUserpageContent());
        info.put("custom_badge_name", user.getCustomBadgeName());
        info.put("custom_badge_icon", user.getCustomBadgeIcon());

        Map<String, Object> statsByMode = new LinkedHashMap<>();
        for (StatsEntity stats : StatsRepository.findAllByUser(user.getId())) {
            statsByMode.put(String.valueOf(stats.getId().getMode()), ApiMappers.stats(stats));
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("info", info);
        body.put("stats", statsByMode);
        body.put("scope", token.getScope());

        ctx.json(body);
    }
}
