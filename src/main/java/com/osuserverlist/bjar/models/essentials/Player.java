package com.osuserverlist.bjar.models.essentials;

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.osu.OsuVersionParser.OsuVersion;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
public class Player {

    public Player(int id, boolean isBot, String osuToken) {
        this.id = id;
        this.isBot = isBot;
        this.osuToken = osuToken;
        this.lastPing = System.currentTimeMillis();
        if (!isBot)
            for (int i = 0; i < modeStats.length; i++) {
                modeStats[i] = new ModeStats();
            }

    }

    private int id;
    private boolean isBot = false;
    private String username;
    private String osuToken;
    private String apiIdent = "";

    private Deque<ServerPacket> packetStack = new ConcurrentLinkedDeque<>();
    private Set<Integer> unlockedAchievements = ConcurrentHashMap.newKeySet();
    private Set<Player> spectators = ConcurrentHashMap.newKeySet();
    private Set<Integer> blocks = ConcurrentHashMap.newKeySet();
    private Set<Integer> friends = ConcurrentHashMap.newKeySet();
    private UserEntity entity = null;
    private OsuVersion osuVersion = null;

    private long lastPing = System.currentTimeMillis();
    private Player spectating = null;
    private Match match = null;
    private boolean stealth = false;
    private boolean tourneyClient = false;
    private boolean restricted = false;
    private boolean relaxEnabled = false;
    private boolean inLobby = false;
    private boolean displayCityLocation = false;
    private boolean friendOnlyDms = false;
    private int silenceEnd = 0;
    private int donorEnd = 0;

    private ModeStats[] modeStats = new ModeStats[9];

    private byte action = 0; // Idle
    private String actionText = ""; // No action description
    private String beatmapMd5 = ""; // No beatmap loaded
    private int mods = 0; // No mods
    private byte gameMode = 0; // osu! standard
    private int realGameMode = 0;
    private int beatmapId = 0; // No beatmap

    private int timezone = 0; // UTC+3
    private short country = 1; // Country ID (1 = US)
    private int clientPrivileges = 1; // Example privilege flags
    private int serverPrivileges = 1; // Example privilege flags
    private float longitude = 1234; // San Francisco
    private float latitude = 4321;
    private int rank = 0;

    private long lastNpBeatmapId = 0;
    private long lastNpBeatmapSetId = 0;

    private int presenceFilter = 0;

    public void sendPacket(ServerPacket packet) {
        if(isBot) return;

        packetStack.push(packet);
    }

    public boolean isSilenced() {
        return silenceEnd > System.currentTimeMillis() / 1000L;
    }

    public boolean canChat() {
        return !isSilenced() && !restricted;
    }

    public int getSilenceEndSeconds() {
        return (int)(silenceEnd - System.currentTimeMillis() / 1000L);
    }

    @Override
    public String toString() {
        return String.format("<%s>(%d)", username, id);
    }

}
