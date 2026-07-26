package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The client update check.
 *
 * <p>An empty body means "nothing to download", which is the honest answer: this
 * server does not host client builds, and handing back osu!'s own update list
 * would invite the client to patch itself against a different endpoint mid
 * session. Without this route the client stalls on a failed request at startup,
 * which is why the empty answer still matters.</p>
 */
@Host("osu.")
@Path("/web/check-updates.php")
@HttpMethod("GET")
public class OsuCheckUpdatesHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        ctx.contentType("text/plain");
        ctx.status(200).result("");
    }
}
