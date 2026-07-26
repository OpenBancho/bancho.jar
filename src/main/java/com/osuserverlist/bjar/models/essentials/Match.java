package com.osuserverlist.bjar.models.essentials;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.osu.match.MatchScoringType;
import com.osuserverlist.bjar.models.osu.match.MatchSpecialMode;
import com.osuserverlist.bjar.models.osu.match.MatchTeamType;
import com.osuserverlist.bjar.models.osu.match.MatchType;
import com.osuserverlist.bjar.models.osu.match.SlotStatus;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.MatchUpdatePacket;

import lombok.Data;

@Data
public class Match {
    public static final int MAX_SLOTS = 16;

    MatchType matchType;
    MatchScoringType scoringType;
	MatchTeamType teamType;

    short matchId;
    boolean inProgress;

    int mods;
    int mode;

    String roomName;
    String roomPassword;
    String beatmapName;
    int beatmapId;
    String beatmapChecksum;
    int hostId;

    MatchSpecialMode specialMode;
	int seed;

    MatchSlot[] slots = new MatchSlot[MAX_SLOTS];
    Set<Player> players = ConcurrentHashMap.newKeySet();
    Set<Player> referees = ConcurrentHashMap.newKeySet();
    ScheduledFuture<?> loadTimeoutTask;

    public String toString() {
        return String.format("<%s> (%d)", roomName, matchId);
    }

    public Integer getSlotIndex(Player player) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots[i] != null && slots[i].getPlayerId() == player.getId()) {
                return i;
            }
        }
        return null;
    }

    public MatchSlot getSlot(Player player) {
        Integer slotIndex = getSlotIndex(player);
        if (slotIndex == null) return null;
        return slots[slotIndex];
    }

    public boolean isLoaded() {
        for(int i = 0; i < MAX_SLOTS; i++) {
            MatchSlot slot = slots[i];
            if(slot.getPlayerId() == 0) continue;
            if(slot.getStatus() == SlotStatus.NO_MAP_LOADED.value) continue;
            if(!slot.isLoaded()) return false;
        }
        return true;
    }

    public Integer getFreeSlot() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if(slots[i].playerId != 0) continue;
            if(slots[i].getStatus() == SlotStatus.LOCKED.value) continue;
            return i;
        }
        return null;
    }

    public void sendPacket(ServerPacket packet) {
        players.forEach(p -> p.sendPacket(packet));
        referees.forEach(p -> p.sendPacket(packet));
    }

    public void enqueUpdate() {
        Server server = App.server;

        players.forEach(p -> {
            p.sendPacket(new MatchUpdatePacket(this));
        });

        referees.forEach(p -> {
            p.sendPacket(new MatchUpdatePacket(this));
        });

        server.playerManager.getAllSessions().stream().filter(p -> p.isInLobby()).forEach(p -> {
            p.sendPacket(new MatchUpdatePacket(this));
        });
    }

}
