package com.osuserverlist.bjar.handlers.api;

import java.io.IOException;
import java.nio.file.Files;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.render.BestReplayService;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/replay_video?id= — an already rendered replay video, as mp4.
 *
 * <p>This only serves files that exist. It never starts a render: that is decided by
 * {@code get_best_replay}, so a visitor cannot make the server render an arbitrary score.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/replay_video")
@HttpMethod("GET")
public class ReplayVideoAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Replay video",
        description = "A rendered replay video (mp4). Only videos that have been rendered are served.",
        tags = { "Scores" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "Score id.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The mp4 file"),
            @OpenApiResponse(status = "404", description = "No video for this score")
        },
        path = "/api/v1/replay_video"
    )
    public void handle(@NotNull Context ctx) throws IOException {
        String raw = ctx.queryParam("id");

        if (raw == null || !raw.matches("\\d{1,12}")) {
            ctx.status(400).json(ApiPagination.error("Invalid score id."));
            return;
        }

        java.nio.file.Path file = BestReplayService.videoPath(Long.parseLong(raw));

        if (!Files.isRegularFile(file)) {
            ctx.status(404).json(ApiPagination.error("Video not found."));
            return;
        }

        // The file name is the score id, which never changes content, so it can be cached for good.
        ctx.header("Cache-Control", "public, max-age=31536000, immutable");
        ctx.contentType("video/mp4");
        ctx.header("Content-Length", String.valueOf(Files.size(file)));
        ctx.result(Files.newInputStream(file));
    }
}
