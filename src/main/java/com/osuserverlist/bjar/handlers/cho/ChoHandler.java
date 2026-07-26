package com.osuserverlist.bjar.handlers.cho;

import java.io.IOException;
import java.util.Deque;
import java.util.Iterator;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

/**
 * Handles the Bancho client protocol: initial login (no {@code osu-token}
 * header, delegated to {@link LoginHandler}) and subsequent packet exchange
 * for already-connected players.
 */
@Host({ "c.", "c4." })
@Path("/")
@HttpMethod("POST")
public class ChoHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(ChoHandler.class);

    private final LoginHandler loginHandler = new LoginHandler();

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String osuToken = ctx.header("osu-token");

        if (osuToken == null) {
            loginHandler.handle(ctx);
        } else {
            handlePacketExchange(ctx, osuToken);
        }
    }

    // ------------------------------------------------------------------
    // Packet exchange (already-connected players)
    // ------------------------------------------------------------------

    private static void handlePacketExchange(Context ctx, String osuToken) {
        BanchoPacketWriter packetWriter = new BanchoPacketWriter();

        Player player = App.server.playerManager.get(osuToken);

        if (player == null) {
            packetWriter.startPacket(ServerPackets.SWITCH_SERVER);
            packetWriter.endPacket();
            ctx.status(HttpStatus.OK).result(packetWriter.getPackets());
            return;
        }

        // The client polls this endpoint continuously while it is online,
        // whether or not it has a PING packet to send, so the request
        // itself is the proof that the session is alive. Counting only
        // PING packets made the cleanup task drop players mid-session.
        player.setLastPing(System.currentTimeMillis());

        byte[] requestBody = ctx.bodyAsBytes();
        if (requestBody.length > 0) {
            try {
                BanchoPacketReader reader = new BanchoPacketReader(requestBody, player);

                while (reader.hasMorePackets()) {
                    try {
                        if (!reader.nextPacket()) {
                            logger.warn("Failed to process packet for player ID={}", player.getId());
                        }
                    } catch (IOException e) {
                        logger.error("Error reading packet: {}", e.getMessage());
                        // Continue processing other packets even if one fails
                    }
                }
            } catch (Exception e) {
                logger.error("Unexpected error processing packets: {}", e.getMessage(), e);
            }
        }

        handlePendingPackets(packetWriter, player);

        ctx.result(packetWriter.getPackets())
                .status(HttpStatus.OK)
                .contentType("application/octet-stream");
    }

    /**
     * Flushes any packets queued on the player's packet stack into the given
     * writer, in the order they were pushed. Also used by {@link LoginHandler}
     * to write the initial batch of login packets.
     */
    public static void handlePendingPackets(BanchoPacketWriter writer, Player player) {
        Deque<ServerPacket> packetStack = player.getPacketStack();

        if (packetStack.isEmpty()) {
            return;
        }

        Iterator<ServerPacket> it = packetStack.descendingIterator();
        while (it.hasNext()) {
            ServerPacketEngine.write(it.next(), writer, player);
        }

        packetStack.clear();
    }
}