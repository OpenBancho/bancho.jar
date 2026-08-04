package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Session bookkeeping the client posts when a play session starts or ends.
 *
 * <p>Official bancho uses it for the "time played" statistics that are already
 * tracked here from score submission, so nothing needs to be stored. The client
 * only cares that the route answers with an empty 200.</p>
 */
@Host("osu.")
@Path("/web/osu-session.php")
@HttpMethod({ "POST", "GET" })
public class OsuSessionHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(OsuSessionHandler.class);

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String action = ctx.formParam("action");

        Player player = OsuWebAuth.authenticate(ctx.formParam("u"), ctx.formParam("h"));

        if (player != null) {
            logger.debug("Session {} for {}", action == null ? "ping" : action, player.getUsername());
        }

        ctx.contentType("text/plain");
        ctx.status(200).result("");
    }
}
