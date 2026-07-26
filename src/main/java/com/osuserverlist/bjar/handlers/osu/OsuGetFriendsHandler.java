package com.osuserverlist.bjar.handlers.osu;

import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host("osu.")
@Path("/web/osu-getfriends.php")
@HttpMethod("GET")
public class OsuGetFriendsHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {

        String username = ctx.queryParam("u");
        String passwordHash = ctx.queryParam("h");
        String apiIdent = String.format("%s|%s", username, passwordHash);

        Server server = App.server;
        Player player = server.playerManager.getByApiIdent(apiIdent);

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        String response = player.getFriends()
                .stream()
                .map(Integer::intValue)
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
        ctx.status(200).result(response);

    }

}
