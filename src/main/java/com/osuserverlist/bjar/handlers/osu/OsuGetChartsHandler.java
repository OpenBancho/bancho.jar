package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Chart listings for the client's "Charts" tab.
 *
 * <p>Official bancho answers with the seasonal beatmap charts. Nothing of the
 * sort exists here, but the client logs an error and keeps retrying while the
 * route is missing, so an empty, well-formed listing is returned instead.</p>
 */
@Host("osu.")
@Path("/web/osu-getcharts.php")
@HttpMethod({ "GET", "POST" })
public class OsuGetChartsHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        ctx.contentType("text/plain");
        ctx.status(200).result("");
    }
}
