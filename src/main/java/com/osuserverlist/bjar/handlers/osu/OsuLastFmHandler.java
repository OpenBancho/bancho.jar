package com.osuserverlist.bjar.handlers.osu;

import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.AntiCheatFlags.LastFMFlags;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host("osu.")
@Path("/web/lastfm.php")
@HttpMethod("GET")
public class OsuLastFmHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = ctx.queryParam("us");
        String passwordHash = ctx.queryParam("ha");
        String flag = ctx.queryParam("b");

        Server server = App.server;

        Player player = server.playerManager.getByApiIdent(username + "|" + passwordHash);

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        if (flag == null || flag.isEmpty()) {
            ctx.result("");
            return;
        }

        // Normal beatmap ID, tell client to stop sending.
        if (flag.charAt(0) != 'a') {
            ctx.result("-3");
            return;
        }

        int flags = Integer.parseInt(flag.substring(1));

        // HQ_ASSEMBLY | HQ_FILE
        if ((flags & (LastFMFlags.HQ_ASSEMBLY | LastFMFlags.HQ_FILE)) != 0) {

            server.playerManager.restrict(player);

            player.sendPacket(new SendMessagePacket(server.botPlayer.getUsername(), "", player.getUsername(), server.botPlayer.getId()));
            server.playerManager.disconnect(player);

            ctx.result("-3");
            return;
        }

        // REGISTRY_EDITS
        if ((flags & LastFMFlags.REGISTRY_EDITS) != 0) {

            // 1/32 chance
            if (ThreadLocalRandom.current().nextInt(32) == 0) {

                server.playerManager.restrict(player);

                server.playerManager.disconnect(player);

                ctx.result("-3");
                return;
            }

            String message = "Hey!\n" +
                    "It appears you have hq!osu's multiaccounting tool (relife) enabled.\n" +
                    "This tool leaves a change in your registry that the osu! client can detect.\n" +
                    "Please re-install relife and disable the program to avoid any restrictions.";

            player.sendPacket(new NotificationPacket(message));

            server.playerManager.disconnect(player);

            ctx.result("-3");
            return;
        }

        ctx.result("");
    }
}
