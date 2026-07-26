package com.osuserverlist.bjar.handlers.osu;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Serves the newest .osu file for a single difficulty.
 *
 * <p>The server keeps no copies of official .osu files, so the request is sent on
 * to osu! itself. That only works when the client was started with -devserver:
 * a client that rewrote its own hosts entries would loop straight back here, so
 * that case is answered with an explanation instead of a redirect.</p>
 */
@Host("osu.")
@Path("/web/maps/{filename}")
@HttpMethod("GET")
public class OsuGetUpdatedBeatmapHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String host = ctx.header("Host");

        if (host != null && host.equalsIgnoreCase("osu.ppy.sh")) {
            ctx.status(200).result("This server only supports the -devserver connection method.");
            return;
        }

        String filename = ctx.pathParam("filename");

        if (filename.isBlank()) {
            ctx.status(404).result("");
            return;
        }

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        ctx.status(301)
                .header("Location", "https://osu.ppy.sh/web/maps/" + encoded)
                .result("");
    }
}
