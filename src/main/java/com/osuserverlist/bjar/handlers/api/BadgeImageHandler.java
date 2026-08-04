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
 * Serves the uploaded picture of a custom badge.
 *
 * <p>It lives under {@code /api/v1} on purpose: that is the one prefix the site
 * already shares with this server, so a profile page can show the picture with
 * no extra host or proxy rule.</p>
 */
@Host({"api.", "server", ""})
@WebEngine.Path("/api/v1/badge/{file}")
@HttpMethod("GET")
public final class BadgeImageHandler implements Handler {

    private static final Path BADGE_DIR =
            Path.of("data", "assets", "badges").toAbsolutePath().normalize();

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String filename = ctx.pathParam("file");

        // Only the exact shape this server writes is served back, so no path of
        // any kind can be smuggled through the parameter.
        if (!filename.matches("[0-9]{1,10}\\.png")) {
            ctx.status(404).result("No such badge.");
            return;
        }

        Path icon = BADGE_DIR.resolve(filename).normalize();

        if (!icon.getParent().equals(BADGE_DIR) || !Files.isRegularFile(icon)) {
            ctx.status(404).result("No such badge.");
            return;
        }

        ctx.contentType("image/png");
        // Short lived: the path stays the same when a supporter replaces theirs.
        ctx.header("Cache-Control", "public, max-age=60");
        ctx.result(Files.readAllBytes(icon));
    }
}
