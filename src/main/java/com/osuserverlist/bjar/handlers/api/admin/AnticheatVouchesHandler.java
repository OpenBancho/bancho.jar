package com.osuserverlist.bjar.handlers.api.admin;

import java.util.List;
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
@Path("/api/v1/admin/anticheat/vouches")
@WebEngine.HttpMethod("GET")
public final class AnticheatVouchesHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Players this server vouches for",
        description = "Active vouches of this game server and the slots left. Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        path = "/api/v1/admin/anticheat/vouches",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        if (!AnticheatClient.isConfigured()) {
            ctx.status(200).json(Map.of("configured", false, "vouches", List.of()));
            return;
        }

        AnticheatApi.forward(ctx, AnticheatClient.vouches());
    }
}
