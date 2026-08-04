package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Leaderboards for clients older than the osz2 era.
 *
 * <p>These builds ask the same question as {@code /web/osu-osz2-getscores.php}
 * but with a smaller parameter set: no mods, no leaderboard type, and the
 * credentials under {@code u} / {@code h} instead of {@code us} / {@code ha}.
 * The response format is identical, so the request is simply handed to the
 * shared implementation, which fills in the missing values with the defaults
 * these clients assume.</p>
 */
@Host("osu.")
@Path("/web/osu-getscores.php")
@HttpMethod("GET")
public class OsuGetScoresHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Osz2GetScoresHandler.respond(ctx);
    }
}
