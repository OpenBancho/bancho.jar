package com.osuserverlist.bjar.handlers.api.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.anticheat.AnticheatClient;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;

/** Shared helpers of the anti-cheat staff handlers (not a route itself). */
final class AnticheatApi {

    private AnticheatApi() {
    }

    /** Passes the anti-cheat's answer to the caller; an error keeps the {@code { "status": message }} shape the other admin routes use. */
    static void forward(Context ctx, AnticheatClient.Result result) {
        if (result.ok()) {
            ctx.status(200).json(result.body());
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.error());
        if (!result.code().isEmpty()) {
            body.put("code", result.code());
        }
        ctx.status(result.status() >= 400 ? result.status() : 502).json(body);
    }

    /** The staff member's name for the anti-cheat's audit log and for the reason the game server shows. */
    static String staffName(int actorId) {
        UserEntity actor = UserRepository.findById(actorId);
        return actor == null || actor.getName() == null ? "staff#" + actorId : actor.getName();
    }
}
