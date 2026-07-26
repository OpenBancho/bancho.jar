package com.osuserverlist.bjar.handlers.web;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.main.Application;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host({ "c.", "c4." })
@Path("/players")
@HttpMethod("GET")
public class PlayerPageHandler implements Handler {

    private final String indexTemplate;

    public PlayerPageHandler() throws IOException {
        this.indexTemplate = new String(
            getClass().getResourceAsStream("/web/players.html").readAllBytes()
        );
    }

    @Override
    public void handle(@NotNull Context ctx) {
        StringBuilder playerHtml = new StringBuilder();

        Set<Integer> listed = new HashSet<>();
        App.server.playerManager.getAllSessions().stream()
                .filter(player -> !player.isTourneyClient())
                .filter(player -> listed.add(player.getId()))
                .forEach(player -> {
                    playerHtml.append(player.getUsername()).append(" (").append(player.getId()).append(")").append("<br>");
                });

        String html = indexTemplate.replace("%players%", playerHtml.toString())
        .replace("%header%", Application.HEADER)
        .replace("%domain%", App.server.enviromentConfig.getDomain())
        .replace("%version%", "bancho.jar <" + BuildInfo.VERSION + ">");
        ctx.html(html);
    }
}
