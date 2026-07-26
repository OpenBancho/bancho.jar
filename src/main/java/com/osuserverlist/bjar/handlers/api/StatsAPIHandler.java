package com.osuserverlist.bjar.handlers.api;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.api.ApiDto.StatsResponse;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.ScoreRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;

@Host("api.")
@Path("/api/v1/get_server_stats")
@HttpMethod("GET")
public class StatsAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Get server statistics",
        description = "Retrieves the current statistics for a specified server.",
        tags = { "Server" },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = StatsResponse.class) },
                description = "Successful response with server statistics"
            ),
            @OpenApiResponse(status = "500", description = "Internal Server Error")
        },
        path = "/api/v1/get_server_stats"
    )
    public void handle(@NotNull Context ctx) throws Exception {

        StatsResponse response = new StatsResponse();
        response.setOnlinePlayers(App.server.playerManager.getOnlineCount());
        response.setTotalPlayers(UserRepository.count());
        response.setMaps(BeatmapRepository.count());
        response.setScores(ScoreRepository.count());

        ctx.json(response);
    }

}
