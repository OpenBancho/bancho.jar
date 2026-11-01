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
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;

@Host({"api.", "server", ""})
@Path("/api/v1/admin/anticheat/vouch")
@WebEngine.HttpMethod("POST")
public final class AnticheatVouchHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Vouch for a player",
        description = "Tells the anti-cheat that this server vouches for the player: a soft restriction and the pp limit stop applying to them here. A restriction confirmed by OpenWatcher cannot be vouched for (409). Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        path = "/api/v1/admin/anticheat/vouch",
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
        if (reason == null || reason.trim().length() < 5) {
            ApiAuth.badRequest(ctx, "A reason of at least 5 characters is required.");
            return;
        }
        reason = reason.trim();

        // A moderator cannot vouch for themselves.
        if (userId == session.getUserId()) {
            ApiAuth.badRequest(ctx, "You cannot vouch for your own account.");
            return;
        }

        if (UserRepository.findById(userId) == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        // The terms of vouching are the anti-cheat's: the word typed after reading them and the version shown travel with the request,
        // and the anti-cheat refuses (400 agreement_required / 409 terms_changed) a vouch that does not carry them.
        String agreement = ApiAuth.stringField(body, "agreement");
        int termsVersion = ApiAuth.intField(body, "terms_version");
        if (termsVersion == Integer.MIN_VALUE) {
            termsVersion = 0;
        }

        String by = AnticheatApi.staffName(session.getUserId());
        AnticheatClient.Result result = AnticheatClient.vouch(userId, reason, by, agreement, termsVersion);

        if (result.ok()) {
            AdminActions.logAnticheat(session.getUserId(), userId, "vouch",
                    "Vouched for the player (accepted the OpenWatcher terms v" + termsVersion + "): " + reason);
        }

        AnticheatApi.forward(ctx, result);
    }
}
