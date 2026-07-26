package com.osuserverlist.bjar.server;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.ModeStats;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.irc.IrcPlayer;
import com.osuserverlist.bjar.packets.server.LoginServerPackets.SilenceInfoPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserQuitPacket;
import com.osuserverlist.bjar.repos.UserRepository;

public class PlayerManager {
    private final Map<String, Player> onlineSessions = new ConcurrentHashMap<>();
    private final Map<String, Player> apiIdentMap = new ConcurrentHashMap<>();
    private final Set<Player> onlinePlayers = ConcurrentHashMap.newKeySet();

    public void add(Player player) {
        onlineSessions.put(player.getOsuToken(), player);
        apiIdentMap.put(player.getApiIdent(), player);
        onlinePlayers.add(player);
    }

    public Player get(String osuToken) {
        return onlineSessions.get(osuToken);
    }

    public Player getByApiIdent(String apiIdent) {
        return apiIdentMap.get(apiIdent);
    }

    public Player getByFilter(Predicate<Player> filter) {
        return onlineSessions.values().stream().filter(filter).findFirst().orElse(null);
    }

    public Player getById(int id) {
        return getByFilter(p -> p.getId() == id);
    }

    public Player getByUsername(String username) {
        return getByFilter(p -> p.getUsername().equalsIgnoreCase(username));
    }

    public Collection<Player> getAllSessions() {
        return onlineSessions.values();
    }

    public void disconnect(Player player) {
        if (player instanceof IrcPlayer ircPlayer) {
            ircPlayer.getClient().detachAndClose("Disconnected by server");
        }

        Server server = App.server;
        for (Channel channel : server.channelManager.getAll()) {
            if (channel.getPlayers().contains(player)) {
                server.channelManager.leaveChannel(channel.getName(), player);
            }
        }

        Match match = player.getMatch();
        if(match != null) {
            server.matchManager.leaveMatch(match, player);
        }

        // Tournament clients referee matches without occupying a slot; make sure
        // they're removed from every match's referee set on disconnect.
        for (Match m : server.matchManager.getAll()) {
            m.getReferees().remove(player);
        }

        onlineSessions.remove(player.getOsuToken());
        if(!onlineSessions.containsValue(player)) {
            onlinePlayers.remove(player);
        }

        // Only drop the api-ident mapping if it still points at this session
        // (tournament clients share one api ident across several sessions).
        if (apiIdentMap.get(player.getApiIdent()) == player) {
            apiIdentMap.remove(player.getApiIdent());
        }

        // Announce the quit only when the account has no remaining sessions.
        boolean stillOnline = onlineSessions.values().stream()
                .anyMatch(p -> p.getId() == player.getId());

        if (!stillOnline) {
            for (Player p : onlineSessions.values()) {
                p.sendPacket(new UserQuitPacket(player.getId()));
            }
        }
    }

    public int getOnlineCount() {
        return onlineSessions.size();
    }

    public void addPriv(Player player, Privileges priv) {
        player.setServerPrivileges(player.getServerPrivileges() | priv.getValue());
        disconnect(player);
    }

    public void removePriv(Player player, Privileges priv) {
        player.setServerPrivileges(player.getServerPrivileges() & ~priv.getValue());
        disconnect(player);
    }

    public void restrict(Player player) {
        player.setServerPrivileges(player.getServerPrivileges() & ~Privileges.UNRESTRICTED.getValue());
        disconnect(player);
    }

    public void unrestrict(Player player) {
        player.setServerPrivileges(player.getServerPrivileges() | Privileges.UNRESTRICTED.getValue());
        disconnect(player);
    }

    public void silence(Player player, int silenceEnd) {
        int silenceSecondsRemaining = (int) (silenceEnd - (System.currentTimeMillis() / 1000L));
        player.setSilenceEnd(silenceEnd);
        player.sendPacket(new SilenceInfoPacket(silenceSecondsRemaining));
    }

    public void unsilence(Player player) {
        player.setSilenceEnd(0);
        player.sendPacket(new SilenceInfoPacket(0));
    }

    public Player getBotPlayer(int id) {
        UserEntity entity = UserRepository.findById(id);

        if (entity == null) {
            return null;
        }

        // TODO: Make this more dynamic enabling mutliple bots

        Player botPlayer = new Player(id, true, UUID.randomUUID().toString());
        botPlayer.setUsername(entity.getName());
        botPlayer.setCountry((short) 245); // satellite provider
        botPlayer.setEntity(entity);

        for (int i = 0; i <= 8; i++) {
            ModeStats modeStats = new ModeStats();
            botPlayer.getModeStats()[i] = modeStats;
        }

        return botPlayer;
    }
}
