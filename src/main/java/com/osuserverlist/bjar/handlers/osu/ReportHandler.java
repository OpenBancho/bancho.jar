package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Older clients post their reports to {@code /web/report.php} instead of
 * {@code /web/osu-report.php}. Both mean the same thing, so this is only an
 * alias for {@link OsuReportHandler}.
 */
@Host("osu.")
@Path("/web/report.php")
@HttpMethod({ "POST", "GET" })
public class ReportHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        OsuReportHandler.process(ctx);
    }
}
