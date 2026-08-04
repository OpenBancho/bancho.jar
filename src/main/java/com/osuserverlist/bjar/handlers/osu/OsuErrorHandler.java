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
 * Crash and exception reports sent by the game client.
 *
 * <p>The client posts here on its own whenever it catches something it could not
 * handle. Nothing is expected back: an empty 200 is enough, and anything else
 * makes the client retry the upload. The report itself is written to the server
 * log so client-side crashes are at least visible.</p>
 */
@Host("osu.")
@Path("/web/osu-error.php")
@HttpMethod("POST")
public class OsuErrorHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(OsuErrorHandler.class);

    /** Stack traces can be enormous; the head of one is enough to identify it. */
    private static final int MAX_LOGGED_CHARS = 2000;

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = ctx.formParam("u");
        String version = ctx.formParam("osuver");
        String feedback = ctx.formParam("feedback");
        String exception = ctx.formParam("exception");
        String stacktrace = ctx.formParam("stacktrace");

        Player player = OsuWebAuth.authenticate(username, ctx.formParam("h"));

        String reporter = player != null
                ? player.getUsername()
                : (username == null || username.isBlank() ? "an anonymous client" : username);

        // Clients that only ping this route to check that it exists send nothing.
        if (isBlank(exception) && isBlank(stacktrace) && isBlank(feedback)) {
            ctx.status(200).result("");
            return;
        }

        logger.warn("Client error report from {} (osu! {}): {} | {} | {}",
                reporter,
                version == null ? "unknown" : version,
                trim(feedback),
                trim(exception),
                trim(stacktrace));

        ctx.status(200).result("");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }

        return value.length() > MAX_LOGGED_CHARS
                ? value.substring(0, MAX_LOGGED_CHARS) + "..."
                : value;
    }
}
