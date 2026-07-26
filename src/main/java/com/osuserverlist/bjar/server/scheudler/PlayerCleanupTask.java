package com.osuserverlist.bjar.server.scheudler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.repos.UserRepository;

public class PlayerCleanupTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(PlayerCleanupTask.class);

    /**
     * How long a session may stay silent before it is considered dead. The
     * client talks to the server every few seconds, so anything past a few
     * minutes means the connection is gone; the generous window is there
     * for players on bad connections.
     */
    private static final long SESSION_SILENCE_LIMIT = 300_000L * 5;

    @Override
    public void run() {
        Server server = App.server;

        server.playerManager.getAllSessions().forEach(player -> {
            if (player.isBot()) {
                return;
            }

            disconnectInactivePlayer(server, player);
            expireSilence(server, player);
            expireSupporter(server, player);
        });
    }

    private void disconnectInactivePlayer(Server server, Player player) {
        long lastSeen = player.getLastPing();

        if (lastSeen == 0) {
            return;
        }

        long silent = System.currentTimeMillis() - lastSeen;

        if (silent > SESSION_SILENCE_LIMIT) {
            logger.info("Auto disconnected {} after {} seconds without a request",
                    player, silent / 1000L);
            server.playerManager.disconnect(player);
        }
    }

    private void expireSilence(Server server, Player player) {
        if (player.getSilenceEnd() == 0 || nowSeconds() <= player.getSilenceEnd()) {
            return;
        }

        UserEntity entity = player.getEntity();
        entity.setSilenceEnd(0);
        UserRepository.save(entity);

        server.playerManager.unsilence(player);

        logger.info("Silence expired for {}", player);
    }

    private void expireSupporter(Server server, Player player) {
        if (player.getDonorEnd() == 0 || nowSeconds() <= player.getDonorEnd()) {
            return;
        }

        // Drop the privilege in place. Going through removePriv would kick
        // the player off the server, which is not what an expiring tag
        // should do; the client picks the change up on its next login.
        player.setServerPrivileges(
                player.getServerPrivileges() & ~Privileges.SUPPORTER.getValue());

        UserEntity entity = player.getEntity();
        entity.setPrivileges(player.getServerPrivileges());
        entity.setDonorEnd(0);
        UserRepository.save(entity);

        logger.info("Supporter expired for {}", player);
    }

    private long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
