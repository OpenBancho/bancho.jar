package com.osuserverlist.bjar.server;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.MatchSlot;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.match.SlotStatus;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelInfoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.ChannelRevokedPacket;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.DisposeMatchPacket;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.MatchJoinFailPacket;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.MatchJoinSuccessPacket;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.MatchTransferHostPacket;

public class MatchManager {
    private static final Logger logger = LoggerFactory.getLogger(MatchManager.class);
    private AtomicInteger matchIdCounter = new AtomicInteger(1);

    private final Map<Short, Match> matchIdMap = new ConcurrentHashMap<>();
    private final Map<Integer, Match> matchHostMap = new ConcurrentHashMap<>();

    public void add(Match match) {
        match.setMatchId((short) matchIdCounter.getAndIncrement());

        matchIdMap.put(match.getMatchId(), match);
        matchHostMap.put(Integer.valueOf(match.getHostId()), match);
    }

    public Match getById(short matchId) {
        return matchIdMap.get(matchId);
    }

    public Match getByHostId(int hostId) {
        return matchHostMap.get(Integer.valueOf(hostId));
    }

    public Match getByFilter(Predicate<Match> filter) {
        return matchIdMap.values().stream().filter(filter).findFirst().orElse(null);
    }

    public Collection<Match> getAll() {
        return matchIdMap.values();
    }

    public void remove(Match match) {
        matchIdMap.remove(match.getMatchId());
        matchHostMap.remove(Integer.valueOf(match.getHostId()));
    }

    public void updateHost(Match match, int newHostId) {
        matchHostMap.remove(Integer.valueOf(match.getHostId()));
        match.setHostId(newHostId);
        matchHostMap.put(Integer.valueOf(newHostId), match);
    }

    public void joinMatch(Match match, Player player) {
        Integer freeSlot = match.getFreeSlot();
        Server server = App.server;

        if (freeSlot == null) {
            logger.warn("Player {} attempted to join full match {}", player.getUsername(), match.toString());
            player.sendPacket(new MatchJoinFailPacket());
            return;
        }

        match.getSlots()[freeSlot].setPlayerId(player.getId());
        match.getSlots()[freeSlot].setStatus((byte) SlotStatus.NOT_READY.value);
        match.enqueUpdate();

        Channel matchChannel = server.channelManager.get("#multi_" + match.getMatchId());
        if (matchChannel == null) {
            logger.warn("Player {} attempted to join match {} with no associated channel", player.getUsername(), match.toString());
            return;
        }

        server.channelManager.joinChannel(matchChannel.getName(), player);

        player.setMatch(match);
        match.getPlayers().add(player);

        logger.info("Player {} joined match {}", player.toString(), match.toString());

        player.sendPacket(new ChannelJoinSuccessPacket(matchChannel.getAlias()));
        player.sendPacket(new ChannelInfoPacket(matchChannel.getAlias(), matchChannel.getDescription(),
                matchChannel.getPlayerCount()));
        player.sendPacket(new MatchJoinSuccessPacket(match));
    }

    public void leaveMatch(Match match, Player player) {
        String channelName = "#multi_" + match.getMatchId();

        Server server = App.server;

        server.channelManager.leaveChannel(channelName, player);
        player.sendPacket(new ChannelRevokedPacket("#multiplayer"));

        server.matchManager.removePlayerFromMatch(match, player);

        if(this.isMatchEmpty(match)) {
            server.matchManager.remove(match);
            server.channelManager.removeChannel(channelName);

            logger.info("Disposed empty match {}", match);

            server.playerManager.getAllSessions().stream()
                    .filter(Player::isInLobby)
                    .forEach(p -> p.sendPacket(new DisposeMatchPacket(match.getMatchId())));

            player.setMatch(null);
            return;
        }

        server.matchManager.transferHostIfNecessary(match, player);

        match.enqueUpdate();

        logger.info("Player {} left match {}", player, match);
        match.getPlayers().remove(player);
        player.setMatch(null);
    }

    public void removePlayerFromMatch(Match match, Player player) {
       MatchSlot slot = match.getSlot(player);
        if (slot != null) {
            slot.reset();
        }else {
            logger.warn("Player {} was not found in match {} slots", player, match);
        }
    }

    public void transferHostIfNecessary(Match match, Player leavingPlayer) {
        if (match.getHostId() != leavingPlayer.getId()) {
            return;
        }

        int newHostId = Arrays.stream(match.getSlots())
                .mapToInt(MatchSlot::getPlayerId)
                .filter(id -> id != 0)
                .findFirst()
                .orElse(0);

        if (newHostId == 0) {
            return;
        }

        this.updateHost(match, newHostId);

        Player newHost = App.server.playerManager.getById(newHostId);
        if (newHost != null) {
            newHost.sendPacket(new MatchTransferHostPacket());
        }
    }

    public boolean isMatchEmpty(Match match) {
        return Arrays.stream(match.getSlots())
                .noneMatch(slot -> slot.getPlayerId() != 0);
    }
}
