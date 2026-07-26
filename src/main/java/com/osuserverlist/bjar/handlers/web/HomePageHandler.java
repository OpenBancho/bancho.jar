package com.osuserverlist.bjar.handlers.web;

import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.main.Application;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host({ "c.", "c4." })
@Path("/")
@HttpMethod("GET")
public class HomePageHandler implements Handler {

    private final String indexTemplate;
    private final String packetList;

    public HomePageHandler() throws IOException {
        this.indexTemplate = new String(
                getClass().getResourceAsStream("/web/index.html").readAllBytes());

        this.packetList = ClientPacketEngine.packetMetadata.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                .map(entry -> {
                    var packet = entry.getKey();
                    var metadata = entry.getValue();

                    String handler = metadata.getHandlerClassName();
                    if (metadata.getHandlerMethodName() != null && !metadata.getHandlerMethodName().isBlank()) {
                        handler += "::" + metadata.getHandlerMethodName();
                    }

                    return String.format(
                            "%s(%d) -> %s",
                            packet.name(),
                            packet.value,
                            handler);
                })
                .collect(Collectors.joining(System.lineSeparator()));
    }

    @Override
    public void handle(@NotNull Context ctx) {
        String html = indexTemplate
                .replace("%players%", String.valueOf(App.server.playerManager.getOnlineCount()))
                .replace("%version%", "bancho.jar <" + BuildInfo.VERSION + ">")
                .replace("%packets%", packetList)
                .replace("%domain%", App.server.enviromentConfig.getDomain())
                .replace("%header%", Application.HEADER);

        ctx.contentType("text/html").result(html);
    }
}
