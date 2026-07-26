package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Star rating lookups made by the client while browsing.
 *
 * <p>Handed straight to osu!, which owns the official difficulty numbers. The
 * redirect is a 307 rather than a 301 so the client repeats it as a POST with the
 * original body intact; a 301 would turn it into a GET and lose the request.</p>
 */
@Host("osu.")
@Path("/difficulty-rating")
@HttpMethod("POST")
public class OsuDifficultyRatingHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        ctx.status(307)
                .header("Location", "https://osu.ppy.sh/difficulty-rating")
                .result("");
    }
}
