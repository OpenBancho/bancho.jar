package com.osuserverlist.bjar.handlers.osu;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host("osu.")
@WebEngine.Path("/ss/{file}")
@HttpMethod("GET")
public class OsuGetScreenshotHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String filename = ctx.pathParam("file");
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            ctx.status(400).result("Invalid filename.");
            return;
        }

        Path screenshotPath = Path.of("data/ss").resolve(filename);
        if (!Files.exists(screenshotPath)) {
            ctx.status(404).result("Screenshot not found.");
            return;
        }

        String contentType;
        if (filename.endsWith(".png")) {
            contentType = "image/png";
        } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            contentType = "image/jpeg";
        } else {
            ctx.status(400).result("Unsupported file type.");
            return;
        }

        ctx.contentType(contentType);
        ctx.result(Files.readAllBytes(screenshotPath));
    }

}
