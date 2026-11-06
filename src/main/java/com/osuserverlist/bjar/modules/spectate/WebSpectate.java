package com.osuserverlist.bjar.modules.spectate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.replay.ReplayFrame;
import com.osuserverlist.bjar.models.osu.replay.ReplayFrameBundle;
import com.osuserverlist.bjar.models.osu.replay.ScoreFrame;
import com.osuserverlist.bjar.modules.irc.IrcPlayer;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.SpectatorJoinedPacket;
import com.osuserverlist.bjar.packets.server.SpectateServerPackets.SpectatorLeftPacket;

import io.javalin.http.sse.SseClient;

/**
 * Live spectating from the website.
 *
 * <p>A stable client only streams its replay frames while somebody spectates it. A web viewer is not a game client, so
 * while at least one viewer watches a player the server joins that player as a <em>virtual spectator</em>: the host is
 * told the bot account started spectating (it shows up in the host's spectator list, so a player always knows they are
 * being watched), which makes the client send {@code SPECTATE_FRAMES}. The frames go to the in-game spectators as before
 * and, decoded, to every web viewer as server-sent events. When the last viewer leaves the bot "stops spectating".
 *
 * <p>Events, each one JSON on a single {@code data:} line:
 * <ul>
 * <li>{@code state} - who is watched and what they are doing: online, playing, beatmap, mods, mode;</li>
 * <li>{@code frames} - one bundle: cursor frames {@code [time, x, y, keys]}, the replay action and the score frame;</li>
 * <li>{@code end} - the player left (only {@code offline} here; finishing a map is an action of a frames event).</li>
 * </ul>
 *
 * <p>The stream is read-only and carries nothing a spectator in the game would not see.
 */
public final class WebSpectate {

    private static final Logger logger = LoggerFactory.getLogger("WebSpectate");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The account that appears in the host's spectator list for the web viewers. */
    private static final int VIRTUAL_SPECTATOR_ID = 1;

    private static final int MAX_PER_HOST = 200;
    private static final int MAX_TOTAL = 2000;

    /** Stable action ids that mean "in gameplay": Playing, Paused, Multiplaying. */
    private static final Set<Integer> PLAYING_ACTIONS = Set.of(2, 10, 12);

    private static final Map<Integer, Set<Viewer>> VIEWERS = new ConcurrentHashMap<>();
    /** Hosts the bot currently "spectates", so the join and the leave are each sent once. */
    private static final Set<Integer> ATTACHED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger TOTAL = new AtomicInteger();

    private static final ScheduledExecutorService KEEPALIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-spectate-keepalive");
        t.setDaemon(true);
        return t;
    });

    static {
        // proxies drop a silent connection; a comment line every 15 s keeps it open and finds dead viewers
        KEEPALIVE.scheduleAtFixedRate(WebSpectate::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    private WebSpectate() {
    }

    /** One SSE connection. Sends are serialised: frames arrive on the packet threads of several clients at once. */
    private static final class Viewer {
        final SseClient client;
        final int hostId;

        Viewer(SseClient client, int hostId) {
            this.client = client;
            this.hostId = hostId;
        }

        synchronized boolean send(String event, String data) {
            if (client.terminated()) {
                return false;
            }

            try {
                client.sendEvent(event, data);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        synchronized boolean comment(String text) {
            if (client.terminated()) {
                return false;
            }

            try {
                client.sendComment(text);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ the SSE endpoint

    /** {@code GET /api/v1/spectate/stream?id=} */
    public static void stream(SseClient client) {
        int hostId;

        try {
            hostId = Integer.parseInt(client.ctx().queryParam("id"));
        } catch (Exception e) {
            client.sendEvent("error", "{\"error\":\"bad id\"}");
            client.close();
            return;
        }

        Viewer viewer = new Viewer(client, hostId);
        boolean[] admitted = { false };

        VIEWERS.compute(hostId, (k, set) -> {
            if (set == null) {
                set = ConcurrentHashMap.newKeySet();
            }

            if (set.size() < MAX_PER_HOST && TOTAL.get() < MAX_TOTAL) {
                set.add(viewer);
                TOTAL.incrementAndGet();
                admitted[0] = true;
            }

            return set.isEmpty() ? null : set;
        });

        if (!admitted[0]) {
            client.sendEvent("error", "{\"error\":\"too many viewers\"}");
            client.close();
            return;
        }

        // Javalin has already sent text/event-stream with X-Accel-Buffering: no, so nginx passes events at once
        client.keepAlive();
        client.onClose(() -> remove(viewer));

        Player host = host(hostId);
        viewer.send("state", state(hostId, host).toString());

        if (host != null) {
            attach(host);
        }
    }

    private static void remove(Viewer viewer) {
        boolean[] last = { false };

        VIEWERS.computeIfPresent(viewer.hostId, (k, set) -> {
            if (set.remove(viewer)) {
                TOTAL.decrementAndGet();
                last[0] = set.isEmpty();
            }

            return set.isEmpty() ? null : set;
        });

        if (last[0]) {
            detach(viewer.hostId);
        }
    }

    // ------------------------------------------------------------------ status, for the profile button

    /** What a viewer would get first: is the player on stable, and are they in gameplay. */
    public static ObjectNode status(int hostId) {
        return state(hostId, host(hostId));
    }

    private static Player host(int id) {
        if (App.server == null) {
            return null;
        }

        // a tournament client shares the id of its owner but never plays; pick the game client
        Player p = App.server.playerManager.getByFilter(x -> x.getId() == id && !x.isBot() && !x.isTourneyClient()
                && !(x instanceof IrcPlayer));

        if (p == null || p.isRestricted()) {
            return null;
        }

        return p;
    }

    private static ObjectNode state(int hostId, Player host) {
        ObjectNode node = JSON.createObjectNode();
        node.put("source", "stable");
        node.put("user_id", hostId);
        node.put("online", host != null);
        node.put("playing", host != null && PLAYING_ACTIONS.contains((int) host.getAction()));

        if (host != null) {
            node.put("username", host.getUsername());
            node.put("action", host.getAction());
            node.put("action_text", host.getActionText());
            node.put("beatmap_id", host.getBeatmapId());
            node.put("beatmap_md5", host.getBeatmapMd5());
            node.put("mods", host.getMods());
            node.put("mode", host.getGameMode());
            node.put("viewers", VIEWERS.getOrDefault(hostId, Set.of()).size());
        }

        return node;
    }

    // ------------------------------------------------------------------ the virtual spectator

    private static void attach(Player host) {
        if (ATTACHED.add(host.getId())) {
            host.sendPacket(new SpectatorJoinedPacket(VIRTUAL_SPECTATOR_ID));
            logger.info("Web viewers started watching {}", host);
        }
    }

    private static void detach(int hostId) {
        if (!ATTACHED.remove(hostId)) {
            return;
        }

        Player host = host(hostId);

        if (host != null) {
            host.sendPacket(new SpectatorLeftPacket(VIRTUAL_SPECTATOR_ID));
            logger.info("Web viewers stopped watching {}", host);
        }
    }

    // ------------------------------------------------------------------ hooks from the packet handlers

    /** Called for every {@code SPECTATE_FRAMES} a client sends. */
    public static void onFrames(Player host, ReplayFrameBundle bundle) {
        Set<Viewer> set = VIEWERS.get(host.getId());

        if (set == null || set.isEmpty()) {
            return;
        }

        broadcast(set, "frames", frames(bundle));
    }

    /** Called when a client changes its status (song select, playing, ...). */
    public static void onAction(Player host) {
        Set<Viewer> set = VIEWERS.get(host.getId());

        if (set == null || set.isEmpty()) {
            return;
        }

        broadcast(set, "state", state(host.getId(), host).toString());
    }

    /** Called when the player's client logs in: viewers that waited on the profile get the frames again. */
    public static void onLogin(Player host) {
        if (host.isBot() || host.isTourneyClient()) {
            return;
        }

        Set<Viewer> set = VIEWERS.get(host.getId());

        if (set == null || set.isEmpty()) {
            return;
        }

        // a fresh client has no spectators: the old join was lost with the old session
        ATTACHED.remove(host.getId());
        attach(host);
        broadcast(set, "state", state(host.getId(), host).toString());
    }

    /** Called when the last session of the player is gone. */
    public static void onDisconnect(Player host) {
        ATTACHED.remove(host.getId());
        Set<Viewer> set = VIEWERS.get(host.getId());

        if (set == null || set.isEmpty()) {
            return;
        }

        broadcast(set, "end", "{\"source\":\"stable\",\"reason\":\"offline\"}");
        broadcast(set, "state", state(host.getId(), null).toString());
    }

    private static void broadcast(Set<Viewer> set, String event, String data) {
        for (Viewer v : set) {
            if (!v.send(event, data)) {
                remove(v);
            }
        }
    }

    private static void heartbeat() {
        try {
            for (Set<Viewer> set : VIEWERS.values()) {
                for (Viewer v : set) {
                    if (!v.comment("ping")) {
                        remove(v);
                    }
                }
            }

            // a host that dropped without a clean logout still holds the bot; let it go once nobody watches
            for (Integer id : ATTACHED) {
                Set<Viewer> set = VIEWERS.get(id);

                if (set == null || set.isEmpty()) {
                    detach(id);
                }
            }
        } catch (Exception e) {
            logger.debug("Web spectate heartbeat failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ encoding

    private static String frames(ReplayFrameBundle bundle) {
        ObjectNode node = JSON.createObjectNode();
        node.put("source", "stable");
        node.put("action", bundle.getAction() == null ? "standard" : bundle.getAction().name().toLowerCase());
        node.put("extra", bundle.getExtra());

        ArrayNode list = node.putArray("frames");

        if (bundle.getFrames() != null) {
            for (ReplayFrame f : bundle.getFrames()) {
                ArrayNode a = list.addArray();
                a.add(f.getTime());
                a.add(round(f.getX()));
                a.add(round(f.getY()));
                a.add(f.getButtonState());
            }
        }

        ScoreFrame s = bundle.getScoreFrame();

        if (s != null) {
            ObjectNode sc = node.putObject("score");
            sc.put("time", s.getTime());
            sc.put("n300", s.getNum300());
            sc.put("n100", s.getNum100());
            sc.put("n50", s.getNum50());
            sc.put("geki", s.getNumGeki());
            sc.put("katu", s.getNumKatu());
            sc.put("miss", s.getNumMiss());
            sc.put("score", Integer.toUnsignedLong(s.getTotalScore()));
            sc.put("combo", s.getCurrentCombo());
            sc.put("max_combo", s.getMaxCombo());
            sc.put("perfect", s.isPerfect());
            // 0..200; the client sends 0 while it has nothing to say
            sc.put("hp", s.getHp());
        }

        return node.toString();
    }

    private static double round(float v) {
        return Math.round(v * 100) / 100.0;
    }
}
