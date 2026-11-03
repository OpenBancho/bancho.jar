package com.osuserverlist.bjar.handlers.api;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Serves the uploaded page background of a profile.
 *
 * <p>Same placement as the banner: under {@code /api/v1}, the one prefix the
 * site already shares with this server, so a profile page can show it with no
 * extra host or proxy rule.</p>
 */
@Host({"api.", "server", ""})
@WebEngine.Path("/api/v1/background/{file}")
@HttpMethod("GET")
public final class BackgroundImageHandler implements Handler {

    private static final Path BACKGROUND_DIR =
            Path.of("data", "assets", "backgrounds").toAbsolutePath().normalize();

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String filename = ctx.pathParam("file");

        // Only the exact shape this server writes is served back, so no path of
        // any kind can be smuggled through the parameter.
        if (!filename.matches("[0-9]{1,10}\\.jpg")) {
            ctx.status(404).result("No such background.");
            return;
        }

        Path background = BACKGROUND_DIR.resolve(filename).normalize();

        if (!background.getParent().equals(BACKGROUND_DIR) || !Files.isRegularFile(background)) {
            ctx.status(404).result("No such background.");
            return;
        }

        ctx.contentType("image/jpeg");
        // Short lived: the path stays the same when a supporter replaces theirs.
        ctx.header("Cache-Control", "public, max-age=60");
        ctx.result(Files.readAllBytes(background));
    }
}
