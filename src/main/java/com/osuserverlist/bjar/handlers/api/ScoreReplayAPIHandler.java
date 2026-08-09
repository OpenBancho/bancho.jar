package com.osuserverlist.bjar.handlers.api;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.api.ApiVisibility;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.ScoreRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_replay — the stored replay of one score, as an .osr download.
 *
 * <p>The game already has an endpoint of its own for this, but it authenticates with
 * in-game credentials and is only reachable from the client. The score page is a web
 * page, so it needs a plain download for a play anybody may look at: the same score
 * that has a page has a replay link, and one that answers 404 has neither.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_replay")
@HttpMethod("GET")
public class ScoreReplayAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Score replay",
        description = "The stored replay for a score, as an .osr file.",
        tags = { "Scores" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "Score id.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The replay file"),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid id"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Score or replay not found"
            )
        },
        path = "/api/v1/get_replay"
    )
    public void handle(@NotNull Context ctx) throws Exception {
        String idRaw = ctx.queryParam("id");
        if (idRaw == null || idRaw.isBlank()) {
            ctx.status(400).json(ApiPagination.error("Must provide a score id!"));
            return;
        }

        long id;
        try {
            id = Long.parseLong(idRaw.trim());
        } catch (NumberFormatException e) {
            ctx.status(400).json(ApiPagination.error("Invalid score id."));
            return;
        }

        ScoreEntity score = ScoreRepository.findById(id);

        // The same answer the score page gets: a play belonging to a restricted account
        // is not there at all, so neither is its replay.
        if (score == null || !ApiVisibility.canView(ctx, score.getUser())) {
            ctx.status(404).json(ApiPagination.error("Score not found."));
            return;
        }

        java.nio.file.Path replay = Paths.get("data", "replays", score.getId() + ".osr");

        if (!Files.isRegularFile(replay)) {
            ctx.status(404).json(ApiPagination.error("Replay not found."));
            return;
        }

        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + score.getId() + ".osr\"");
        ctx.result(Files.readAllBytes(replay));
    }
}
