package com.osuserverlist.bjar.handlers.api.admin;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.modules.admin.AdminActions;
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
@Path("/api/v1/admin/anticheat/vouch/revoke")
@WebEngine.HttpMethod("POST")
public final class AnticheatVouchRevokeHandler implements Handler {

    @Override
    @OpenApi(
        summary = "End a vouch",
        description = "Ends this server's vouch for the player. Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        path = "/api/v1/admin/anticheat/vouch/revoke",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        int userId = ApiAuth.intField(body, "user_id");
        if (userId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        String reason = ApiAuth.stringField(body, "reason");
        reason = reason == null || reason.isBlank() ? "No reason given" : reason.trim();

        String by = AnticheatApi.staffName(session.getUserId());
        AnticheatClient.Result result = AnticheatClient.revoke(userId, reason, by);

        if (result.ok()) {
            AdminActions.logAnticheat(session.getUserId(), userId, "vouch_revoke", "Ended the vouch: " + reason);
        }

        AnticheatApi.forward(ctx, result);
    }
}
