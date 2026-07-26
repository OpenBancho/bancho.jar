package com.osuserverlist.bjar.packets.client;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.GameMode;
import com.osuserverlist.bjar.models.osu.Mods;
import com.osuserverlist.bjar.models.osu.OsuClientModels.PresenceFilter;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserPresencePacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;

public class UserStatsPresencePackets {

    private final static Logger logger = LoggerFactory.getLogger(UserStatsPresencePackets.class);

    @ClientPacket(ClientPackets.USER_PRESENCE_REQUEST)
    public boolean userPresenceRequest(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        List<Integer> userIds = reader.readIntList();

        logger.debug("Player {} requested presence info for user IDs {}", player, userIds);

        for (Integer userId : userIds) {

            Player requestedPlayer = App.server.playerManager.getById(userId);

            if (requestedPlayer != null) {
                player.sendPacket(new UserPresencePacket(requestedPlayer));
            } else {
                logger.warn("Requested player not found for {}", player.toString());
            }

        }

        return true;
    }

    @ClientPacket(ClientPackets.RECEIVE_UPDATES)
    public boolean receiveUpdates(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        int presenceFilter = reader.readInt();
        PresenceFilter filter = PresenceFilter.values()[presenceFilter];

        if(filter == null) {
            logger.warn("Player {} send invalid presence filter value {}", player, presenceFilter);
            return true;
        }

        player.setPresenceFilter(filter.id);
        return true;
    }

    @ClientPacket(ClientPackets.USER_STATS_REQUEST)
    public boolean userStatsRequest(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        List<Integer> userIds = reader.readIntList();

        userIds.forEach(id -> {
            if(player.getId() == id) return;

            Player requestedPlayer = App.server.playerManager.getById(id);

            if (requestedPlayer != null) {
                player.sendPacket(new UserStatsPacket(requestedPlayer));
            }
        });

        return true;
    }

    @ClientPacket(ClientPackets.REQUEST_STATUS_UPDATE)
    public boolean requestStatusUpdate(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        player.sendPacket(new UserStatsPacket(player));
        return true;
    }

    @ClientPacket(ClientPackets.CHANGE_ACTION)
    public boolean changeAction(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        byte status = reader.readByte();
        String statusText = reader.readString();
        String beatmapMd5 = reader.readString();
        int mods = reader.readInt();
        byte gameMode = reader.readByte();
        int beatmapId = reader.readInt();

        GameMode realGameMode = GameMode.fromValue(gameMode, mods);
        boolean newRelax =
            (mods & Mods.Relax.getValue()) != 0 ||
            (mods & Mods.Relax2.getValue()) != 0;

        player.setRelaxEnabled(newRelax);
        player.setRealGameMode(realGameMode.getValue());

        player.setAction(status);
        player.setActionText(statusText);
        player.setBeatmapMd5(beatmapMd5);
        player.setMods(mods);
        player.setGameMode(gameMode);
        player.setBeatmapId(beatmapId);

        for (Player onlinePlayer : App.server.playerManager.getAllSessions()) {
            onlinePlayer.sendPacket(new UserStatsPacket(player));
        }

        return true;
    }
}
