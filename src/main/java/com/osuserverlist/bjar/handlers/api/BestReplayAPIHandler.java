package com.osuserverlist.bjar.handlers.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.render.BestReplayService;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_best_replay — the best osu!standard score of the server and the state of its replay video.
 *
 * <p>The video is rendered on first ask and kept on disk. Until it exists the answer is {@code "rendering"} and the
 * caller asks again a little later; once it is {@code "ready"} the {@code video_url} can be fetched.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_best_replay")
@HttpMethod("GET")
public class BestReplayAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Best score video",
        description = "The highest pp osu!standard score of a public account with its rendered replay video. "
                + "status is ready, rendering or unavailable.",
        tags = { "Server" },
        responses = {
            @OpenApiResponse(status = "200", description = "The best score and its video state")
        },
        path = "/api/v1/get_best_replay"
    )
    public void handle(@NotNull Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        BestReplayService.Best best = BestReplayService.pickBest();

        if (best == null) {
            body.put("status", "unavailable");
            ctx.header("Cache-Control", "no-store");
            ctx.json(body);
            return;
        }

        BestReplayService.State state = BestReplayService.ensure(best);

        body.put("status", state.name().toLowerCase());
        body.put("score", ApiMappers.score(best.score(), false));
        body.put("player", ApiMappers.userRef(best.score().getUser()));
        body.put("beatmap", ApiMappers.beatmap(best.beatmap()));

        if (state == BestReplayService.State.READY) {
            body.put("video_url", "/api/v1/replay_video?id=" + best.score().getId());
            // lets the site tell a re-rendered clip from the copy it already holds
            body.put("video_version", BestReplayService.RENDER_VERSION);
        }

        // a rendering answer must not be cached: the next ask is expected to differ
        ctx.header("Cache-Control", state == BestReplayService.State.READY ? "public, max-age=60" : "no-store");
        ctx.json(body);
    }
}
