package com.osuserverlist.bjar.handlers.api;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.spectate.WebSpectate;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/spectate/status?id= — whether a player is in the stable client and in gameplay, so a profile can offer
 * to watch them. The frames themselves are the server-sent events of {@code /api/v1/spectate/stream?id=}.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/spectate/status")
@HttpMethod("GET")
public class SpectateStatusAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Live spectate status",
        description = "online and playing for a player in the stable client, with the beatmap, mods and mode.",
        tags = { "Players" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "Player id.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The player's state"),
            @OpenApiResponse(status = "400", description = "Missing or invalid id")
        },
        path = "/api/v1/spectate/status"
    )
    public void handle(@NotNull Context ctx) {
        int id;

        try {
            id = Integer.parseInt(ctx.queryParam("id"));
        } catch (Exception e) {
            ctx.status(400).json(java.util.Map.of("error", "id is required"));
            return;
        }

        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/json").result(WebSpectate.status(id).toString());
    }
}
