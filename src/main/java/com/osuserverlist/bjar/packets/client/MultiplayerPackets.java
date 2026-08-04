package com.osuserverlist.bjar.packets.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Match;
import com.osuserverlist.bjar.models.essentials.MatchSlot;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Mods;
import com.osuserverlist.bjar.models.osu.match.MatchSpecialMode;
import com.osuserverlist.bjar.models.osu.match.MatchTeams;
import com.osuserverlist.bjar.models.osu.match.SlotStatus;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.*;
import com.osuserverlist.bjar.packets.server.MultiplayerServerPackets.*;

public class MultiplayerPackets {

    private final static Logger logger = LoggerFactory.getLogger(MultiplayerPackets.class);

    @ClientPacket(ClientPackets.JOIN_LOBBY)
    public boolean joinLobby(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        player.setInLobby(true);

        for (Match match : App.server.matchManager.getAll()) {
            player.sendPacket(new NewMatchPacket(match));
        }

        return true;
    }

    @ClientPacket(ClientPackets.PART_LOBBY)
    public boolean leaveLobby(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        player.setInLobby(false);
        player.sendPacket(new ChannelRevokedPacket("#lobby"));
        return true;
    }

    @ClientPacket(ClientPackets.CREATE_MATCH)
    public boolean createMatch(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = reader.readMatch();

        if(!player.canChat()) {
            logger.debug("Player {} attempted to create a match but is restricted or silenced", player.getUsername());
            player.sendPacket(new MatchJoinFailPacket());
            return true;
        }

        App.server.matchManager.add(match);

        String channelName = "#multi_" + match.getMatchId();
        Channel matchChannel = Channel.builder()
                .id(channelName)
                .name(channelName)
                .alias("#multiplayer")
                .description("Multiplayer lobby for match " + match.getMatchId() + ".")
                .autoJoin(false)
                .readPriv(0)
                .writePriv(0)
                .visible(false)
                .build();

        App.server.channelManager.add(matchChannel);
        App.server.channelManager.forceJoinChannel(channelName, player);
        player.setMatch(match);

        int freeSlot = match.getFreeSlot();
        match.getSlots()[freeSlot].setPlayerId(player.getId());
        match.getSlots()[freeSlot].setStatus(SlotStatus.NOT_READY.byteValue);
        match.getPlayers().add(player);

        logger.info("Player {} created a match {}", player.toString(), match.toString());
        player.sendPacket(new MatchJoinSuccessPacket(match));
        player.sendPacket(new ChannelJoinSuccessPacket(matchChannel.getAlias()));
        player.sendPacket(new ChannelInfoPacket(matchChannel.getAlias(), matchChannel.getDescription(),
                matchChannel.getPlayerCount()));
        return true;
    }

    @ClientPacket(ClientPackets.JOIN_MATCH)
    public boolean joinMatch(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        int matchId = reader.readInt();
        String password = "";

        Server server = App.server;
        Match match = server.matchManager.getById((short) matchId);

        if (match == null) {
            logger.warn("Player {} attempted to join non-existent match {}", player.getUsername(), matchId);
            return true;
        }

        if (match.getRoomPassword().length() > 0) {
            password = reader.readString();
        }

        if(!player.canChat()) {
            logger.debug("Player {} attempted to join match {} but is restricted or silenced", player.getUsername(), matchId);
            player.sendPacket(new MatchJoinFailPacket());
            return true;
        }

        if (match.getRoomPassword().length() > 0 && !match.getRoomPassword().equals(password)) {
            logger.debug("Player {} attempted to join match {} with incorrect password", player.getUsername(), matchId);
            player.sendPacket(new MatchJoinFailPacket());
            return true;
        }

        server.matchManager.joinMatch(match, player);

        return true;

    }

    @ClientPacket(ClientPackets.PART_MATCH)
    public boolean partMatch(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Server server = App.server;

        Match match = server.matchManager.getAll().stream()
                .filter(m -> Arrays.stream(m.getSlots())
                        .anyMatch(slot -> slot.getPlayerId() == player.getId()))
                .findFirst()
                .orElse(null);

        if (match == null) {
            player.setMatch(null);
            return true;
        }

        server.matchManager.leaveMatch(match, player);
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_READY)
    public boolean matchReady(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        if (player.getMatch() == null) {
            return true;
        }

        MatchSlot slot = player.getMatch().getSlot(player);
        if (slot == null) {
            return true;
        }

        Match playerMatch = player.getMatch();
        if (playerMatch == null) {
            return true;
        }

        slot.setStatus((byte) SlotStatus.READY.value);
        playerMatch.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_NOT_READY)
    public boolean matchNotReady(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        if (player.getMatch() == null) {
            return true;
        }

        Match playerMatch = player.getMatch();
        if (playerMatch == null) {
            logger.warn("Player {} sent MATCH_NOT_READY but is not in a match", player);
            return true;
        }

        MatchSlot slot = player.getMatch().getSlot(player);
        if (slot == null) {
            logger.warn("Player {} sent MATCH_NOT_READY but is not in a match slot", player);
            return true;
        }

        slot.setStatus((byte) SlotStatus.NOT_READY.value);
        playerMatch.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_CHANGE_SLOT)
    public boolean matchChangeSlot(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            return true;
        }

        int slotId = reader.readInt();

        if (slotId < 0 || slotId >= Match.MAX_SLOTS) {
            return true;
        }

        MatchSlot targetSlot = match.getSlots()[slotId];

        if (targetSlot.getStatus() != (byte) SlotStatus.OPEN.value) {
            return true;
        }

        MatchSlot currentSlot = null;

        for (MatchSlot slot : match.getSlots()) {
            if (slot.getPlayerId() == player.getId()) {
                currentSlot = slot;
                break;
            }
        }

        if (currentSlot == null) {
            return true;
        }

        // copy current slot into target slot
        targetSlot.setPlayerId(currentSlot.getPlayerId());
        targetSlot.setStatus(currentSlot.getStatus());
        targetSlot.setTeam(currentSlot.getTeam());
        targetSlot.setMods(currentSlot.getMods());
        targetSlot.setLoaded(currentSlot.isLoaded());
        targetSlot.setSkipped(currentSlot.isSkipped());

        // reset old slot
        currentSlot.setPlayerId(0);
        currentSlot.setStatus((byte) SlotStatus.OPEN.value);
        currentSlot.setTeam((byte) 0);
        currentSlot.setMods(0);
        currentSlot.setLoaded(false);
        currentSlot.setSkipped(false);

        match.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_CHANGE_TEAM)
    public boolean matchChangeTeam(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} tried to change team but is not in a match", player.getUsername());
            return true;
        }

        MatchSlot slot = match.getSlot(player);
        if (slot == null) {
            logger.warn("Player {} tried to change team but is not in a valid slot", player.getUsername());
            return true;
        }

        MatchTeams curTeam = MatchTeams.values()[slot.getTeam()];
        MatchTeams newTeam;

        if (curTeam == MatchTeams.BLUE) {
            newTeam = MatchTeams.RED;
        } else if (curTeam == MatchTeams.RED) {
            newTeam = MatchTeams.BLUE;
        } else {
            return true;
        }

        slot.setTeam(newTeam.byteValue);

        match.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_NO_BEATMAP)
    public boolean matchNoBeatmap(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_NO_BEATMAP but is not in a match", player);
            return true;
        }

        MatchSlot slot = match.getSlot(player);
        if (slot == null) {
            logger.warn("Player {} sent MATCH_NO_BEATMAP but is not in a match slot", player);
            return true;
        }

        slot.setStatus(SlotStatus.NO_MAP_LOADED.byteValue);
        match.enqueUpdate();
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_HAS_BEATMAP)
    public boolean matchHasBeatmap(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            return true;
        }

        MatchSlot slot = match.getSlot(player);
        if (slot == null) {
            return true;
        }

        slot.setStatus(SlotStatus.NOT_READY.byteValue);
        match.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_TRANSFER_HOST)
    public boolean matchTransferHost(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        int slotId = reader.readInt();

        Server server = App.server;
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_TRANSFER_HOST but is not in a match", player);
            return false;
        }

        MatchSlot slot = match.getSlots()[slotId];
        if (slot == null || slot.getPlayerId() == 0) {
            logger.warn("Player {} sent MATCH_TRANSFER_HOST but slot {} is empty", player, slotId);
            return false;
        }

        Player newHost = server.playerManager.getById(slot.getPlayerId());
        if (newHost == null) {
            logger.warn("Player {} sent MATCH_TRANSFER_HOST but slot {} has no player", player, slotId);
            return false;
        }

        server.matchManager.updateHost(match, newHost.getId());
        newHost.sendPacket(new MatchTransferHostPacket());
        logger.info("Player {} transferred host to player {} in match {}", player, newHost, match);
        match.enqueUpdate();
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_LOCK)
    public boolean matchLock(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            return true;
        }

        if (match.getHostId() != player.getId()) {
            return true;
        }

        int slotId = reader.readInt();
        if (slotId < 0 || slotId >= Match.MAX_SLOTS) {
            return true;
        }

        MatchSlot slot = match.getSlots()[slotId];
        Server server = App.server;

        if (slot.getStatus() == (byte) SlotStatus.LOCKED.value) {
            slot.setStatus((byte) SlotStatus.OPEN.value);
            match.enqueUpdate();
            return true;
        }

        if (slot.getPlayerId() == player.getId()) {
            return true;
        }

        if (slot.getPlayerId() != 0) {
            int playerId = slot.getPlayerId();
            slot.reset();

            // Kick player from match
            Player p = server.playerManager.getById(playerId);
            p.sendPacket(new MatchUpdatePacket(match));
            p.setMatch(null);
        }

        slot.setStatus((byte) SlotStatus.LOCKED.value);
        match.enqueUpdate();
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_CHANGE_PASSWORD)
    public boolean matchChangePassword(BanchoPacket packet, BanchoPacketReader reader, Player player)
            throws IOException {
        Match match = reader.readMatch();

        Server server = App.server;
        Match playerMatch = server.matchManager.getByHostId(player.getId());

        if (playerMatch != null) {
            playerMatch.setRoomPassword(match.getRoomPassword());
            playerMatch.enqueUpdate();
        }
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_CHANGE_MODS)
    public boolean matchChangeMods(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            return true;
        }

        int mods = reader.readInt();

        if (match.getSpecialMode() == MatchSpecialMode.FREE_MOD) {
            if (match.getHostId() == player.getId()) {
                match.setMods(mods & Mods.SPEED_CHANGING_MODS);
            }

            MatchSlot slot = match.getSlot(player);
            if (slot != null) {
                slot.setMods(mods & ~Mods.SPEED_CHANGING_MODS);
            }
        } else {
            if (match.getHostId() != player.getId()) {
                return true;
            }

            match.setMods(mods);
        }

        match.enqueUpdate();
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_CHANGE_SETTINGS)
    public boolean matchChangeSettings(BanchoPacket packet, BanchoPacketReader reader, Player player)
            throws IOException {
        Server server = App.server;

        Match match = reader.readMatch();
        Match playerMatch = server.matchManager.getByHostId(player.getId());

        if (playerMatch == null) {
            return true;
        }

        /*
         * Map changed
         */
        if (playerMatch.getBeatmapId() != match.getBeatmapId()) {

            BeatmapEntity beatmap = null;

            if (match.getBeatmapId() > 0) {
                beatmap = server.osuAPIHandler.getBeatmapById(match.getBeatmapId());
            }

            for (MatchSlot slot : playerMatch.getSlots()) {
                if (slot.getStatus() == SlotStatus.READY.byteValue) {
                    slot.setStatus(SlotStatus.NOT_READY.byteValue);
                }

                Player slotPlayer = server.playerManager.getById(slot.getPlayerId());

                if (slotPlayer != null && beatmap != null) {
                    slotPlayer.sendPacket(new SendMessagePacket(
                            server.botPlayer.getUsername(),
                            "Selected: " + beatmap.toEmbed(),
                            "#multiplayer",
                            server.botPlayer.getId()));
                }
            }

            playerMatch.setBeatmapId(match.getBeatmapId());
            playerMatch.setBeatmapChecksum(match.getBeatmapChecksum());
            playerMatch.setBeatmapName(match.getBeatmapName());
        }

        /*
         * Team type changed.
         *
         */
        if (playerMatch.getTeamType() != match.getTeamType()) {

            byte defaultTeam;

            switch (match.getTeamType()) {
                case HEAT_TO_HEAD:
                case TAG_COOP:
                    defaultTeam = MatchTeams.NEUTRAL.byteValue;
                    break;

                case TEAM_VS:
                case TAG_TEAM_VS:
                default:
                    defaultTeam = MatchTeams.RED.byteValue;
                    break;
            }

            for (MatchSlot slot : playerMatch.getSlots()) {
                if (slot.getPlayerId() != 0) {
                    slot.setTeam(defaultTeam);
                }
            }

            playerMatch.setTeamType(match.getTeamType());
        }

        /*
         * Remaining settings
         */
        playerMatch.setMode(match.getMode());
        playerMatch.setMods(match.getMods());
        playerMatch.setMatchType(match.getMatchType());
        playerMatch.setRoomPassword(match.getRoomPassword());
        playerMatch.setRoomName(match.getRoomName());
        playerMatch.setSpecialMode(match.getSpecialMode());
        playerMatch.setScoringType(match.getScoringType());

        playerMatch.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_START)
    public boolean matchStart(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} tried to start a match but is not in a match", player.toString());
            return true;
        }

        if (match.getHostId() != player.getId()) {
            logger.warn("Player {} tried to start a match but is not the host", player.toString());
            return true;
        }

        for (int i = 0; i < match.getSlots().length; i++) {
            MatchSlot slot = match.getSlots()[i];
            slot.setSkipped(false);
            slot.setLoaded(false);
            if (slot.getPlayerId() == 0)
                continue;
            if (slot.getStatus() == SlotStatus.NO_MAP_LOADED.value)
                continue;
            slot.setStatus(SlotStatus.PLAYING.byteValue);
        }

        // The match is now running. Persist this on the match so it is
        // serialized in every subsequent match state (writeMatch -> isInProgress).
        // osu!tourney relies on this flag to know a match has started and to
        // begin spectating its players.
        match.setInProgress(true);

        match.getPlayers().forEach(p -> {
            p.sendPacket(new MatchStartClientPacket(match));
        });

        // Notify referees (the osu!tourney manager) and lobby watchers that the
        // match is in progress with its slots now PLAYING, so they refresh and
        // start spectating. MatchStart itself is only for the actual players.
        match.enqueUpdate();

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_LOAD_COMPLETE)
    public boolean matchLoadComplete(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_LOAD_COMPLETE but is not in a match", player);
            return false;
        }

        MatchSlot slot = match.getSlot(player);
        if (slot == null) {
            logger.warn("Player {} sent MATCH_LOAD_COMPLETE but is not in a match slot", player);
            return false;
        }

        slot.setLoaded(true);

        boolean isLoaded = match.isLoaded();

        if (match.getLoadTimeoutTask() == null) {
            match.setLoadTimeoutTask(
                    App.server.executor.schedule(() -> {
                        sendMatchAllPlayersLoadedPacket(match);
                        match.setLoadTimeoutTask(null);
                    }, 30, TimeUnit.SECONDS));
        }

        if (isLoaded) {
            if (match.getLoadTimeoutTask() != null) {
                match.getLoadTimeoutTask().cancel(false);
                match.setLoadTimeoutTask(null);
            }

            sendMatchAllPlayersLoadedPacket(match);
        }

        return true;
    }

    /**
     * The skip button during a multiplayer intro.
     *
     * <p>The intro is only actually skipped once everyone who is playing has
     * pressed it, so each request is broadcast as a marker first and the real
     * skip goes out when the last one arrives. Players who are not playing (open,
     * locked or unready slots) are not waited for, otherwise a single spectator
     * would hold the whole lobby in the intro.</p>
     */
    @ClientPacket(ClientPackets.MATCH_SKIP_REQUEST)
    public boolean matchSkipRequest(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();

        if (match == null) {
            logger.warn("Player {} sent MATCH_SKIP_REQUEST but is not in a match", player);
            return false;
        }

        MatchSlot slot = match.getSlot(player);

        if (slot == null) {
            logger.warn("Player {} sent MATCH_SKIP_REQUEST but is not in a match slot", player);
            return false;
        }

        if (slot.isSkipped()) {
            return true;
        }

        slot.setSkipped(true);

        int slotIndex = match.getSlotIndex(player);

        match.sendPacket(new MatchPlayerSkippedPacket(slotIndex));

        boolean everyoneSkipped = true;

        for (MatchSlot s : match.getSlots()) {
            if (s.getStatus() == SlotStatus.PLAYING.byteValue && !s.isSkipped()) {
                everyoneSkipped = false;
                break;
            }
        }

        if (everyoneSkipped) {
            match.sendPacket(new MatchSkipPacket());
        }

        return true;
    }

    /**
     * The "invite" button in the client, and what {@code !mp invite} ends up
     * sending too. The invitation is a chat line carrying an {@code osump://}
     * link, which is what the client turns into a clickable join button.
     */
    @ClientPacket(ClientPackets.MATCH_INVITE)
    public boolean matchInvite(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        int userId = reader.readInt();

        Match match = player.getMatch();

        if (match == null) {
            logger.warn("Player {} sent MATCH_INVITE but is not in a match", player);
            return true;
        }

        Player target = App.server.playerManager.getById(userId);

        if (target == null || target.isBot()) {
            player.sendPacket(new SendMessagePacket(
                    App.server.botPlayer.getUsername(),
                    "That player is not online right now.",
                    player.getUsername(),
                    App.server.botPlayer.getId()));
            return true;
        }

        sendInvite(player, target, match);
        return true;
    }

    /** Builds and delivers the clickable invitation line. */
    public static void sendInvite(Player sender, Player target, Match match) {
        String password = match.getRoomPassword() == null ? "" : match.getRoomPassword();

        String message = String.format("Come join my multiplayer match: [osump://%d/%s %s]",
                match.getMatchId(), password, match.getRoomName());

        target.sendPacket(new MatchInvitePacket(
                sender.getUsername(),
                message,
                target.getUsername(),
                sender.getId()));
    }

    public void sendMatchAllPlayersLoadedPacket(Match match) {
        match.getPlayers().forEach(p -> {
            MatchSlot slot = match.getSlot(p);
            if (slot.getStatus() != SlotStatus.NO_MAP_LOADED.byteValue) {
                p.sendPacket(new MatchAllPlayersLoadedPacket());
            }
        });
    }

    @ClientPacket(ClientPackets.MATCH_SCORE_UPDATE)
    public boolean matchScoreUpdate(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_SCORE_UPDATE but is not in a match", player);
            return false;
        }

        Integer slot = match.getSlotIndex(player);
        if (slot == null) {
            logger.warn("Player {} has no slot in match {}", player, match.getMatchId());
            return false;
        }

        // Copy the original payload exactly as the client sent it.
        byte[] playData = reader.getCurrentPacketBody().clone();

        // ScoreFrame.id is the 5th byte (offset 4) of the payload.
        playData[4] = (byte) slot.intValue();

        // Relay live score frames to the whole match, including referees
        // (the osu!tourney manager) so its scoreboard updates during play.
        match.sendPacket(new MatchScoreUpdatePacket(playData));

        return true;
    }

    @ClientPacket(ClientPackets.MATCH_FAILED)
    public boolean matchFailed(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_FAILED but is not in a match", player);
            return false;
        }

        Integer slotIndex = match.getSlotIndex(player);
        if (slotIndex == null) {
            logger.warn("Player {} sent MATCH_FAILED but is not in a match slot", player);
            return false;
        }

        match.sendPacket(new MatchPlayerFailedPacket(slotIndex));
        return true;
    }

    @ClientPacket(ClientPackets.MATCH_COMPLETE)
    public boolean matchComplete(BanchoPacket packet, BanchoPacketReader reader, Player player) throws IOException {
        Match match = player.getMatch();
        if (match == null) {
            logger.warn("Player {} sent MATCH_COMPLETE but is not in a match", player);
            return true;
        }

        MatchSlot slot = match.getSlot(player);
        slot.setFinished(true);

        boolean allFinished = true;
        for (MatchSlot s : match.getSlots()) {
            if(s.getStatus() == SlotStatus.PLAYING.byteValue && !s.isFinished()) {
                allFinished = false;
                break;
            }
        }

        if(allFinished) {
            match.getPlayers().forEach(p -> {
                p.sendPacket(new MatchCompletePacket());
                match.getSlot(p).setStatus(SlotStatus.NOT_READY.byteValue);
            });

            // Match finished: clear the in-progress flag so referees/lobby see
            // it return to an idle state.
            match.setInProgress(false);
            match.enqueUpdate();

        }

        return true;
    }

}
