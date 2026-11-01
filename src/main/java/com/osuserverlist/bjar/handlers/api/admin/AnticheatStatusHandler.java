package com.osuserverlist.bjar.handlers.api.admin;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.modules.anticheat.AnticheatClient;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;

@Host({"api.", "server", ""})
@Path("/api/v1/admin/anticheat/status")
@WebEngine.HttpMethod("GET")
public final class AnticheatStatusHandler implements Handler {

    @Override
    @OpenApi(
        summary = "What the anti-cheat says about a player",
        description = "Restriction kind (soft / hard), whether this server vouches for the player, and the free vouch slots. Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        path = "/api/v1/admin/anticheat/status",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        int userId;
        try {
            userId = Integer.parseInt(ctx.queryParam("user_id"));
        } catch (RuntimeException e) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        if (!AnticheatClient.isConfigured()) {
            ctx.status(200).json(Map.of("configured", false));
            return;
        }

        AnticheatApi.forward(ctx, AnticheatClient.status(userId));
    }
}
