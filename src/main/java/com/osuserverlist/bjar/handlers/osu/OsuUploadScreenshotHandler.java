package com.osuserverlist.bjar.handlers.osu;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UploadedFile;

@Host("osu.")
@Path("/web/osu-screenshot.php")
@HttpMethod("POST")
public class OsuUploadScreenshotHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = ctx.formParamAsClass("u", String.class).required().get();
        String passwordHash = ctx.formParamAsClass("p", String.class).required().get();

        Server server = App.server;
        Player player = server.playerManager.getByApiIdent(String.format("%s|%s", username, passwordHash));

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        UploadedFile screenshot = ctx.uploadedFile("ss");
        if (screenshot == null) {
            ctx.status(400).result("Screenshot file missing.");
            return;
        }

        byte[] bytes = screenshot.content().readAllBytes();
        if (bytes.length > (4 * 1024 * 1024)) { // 4 MB limit
            ctx.status(400).result("Screenshot file too large.");
            return;
        }

        boolean isPng = bytes.length > 8 &&
                bytes[0] == (byte) 0x89 &&
                bytes[1] == (byte) 0x50 &&
                bytes[2] == (byte) 0x4E &&
                bytes[3] == (byte) 0x47;

        boolean isJpeg = bytes.length > 4 &&
                bytes[0] == (byte) 0xFF &&
                bytes[1] == (byte) 0xD8 &&
                bytes[bytes.length - 2] == (byte) 0xFF &&
                bytes[bytes.length - 1] == (byte) 0xD9;

        String extension;
        if (isPng) {
            extension = "png";
        } else if (isJpeg) {
            extension = "jpg";
        } else {
            ctx.status(400).result("Unsupported screenshot format. Only PNG and JPEG are allowed.");
            return;
        }

        File screenshotPath = new File("data/ss/" + UUID.randomUUID() + "." + extension);
        Files.write(screenshotPath.toPath(), bytes);
        ctx.result(screenshotPath.getName());
    }

}
