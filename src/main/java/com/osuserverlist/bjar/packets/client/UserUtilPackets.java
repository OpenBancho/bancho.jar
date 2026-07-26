package com.osuserverlist.bjar.packets.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.FriendsListPacket;
import com.osuserverlist.bjar.repos.RelationshipRepository;
import com.osuserverlist.bjar.repos.UserRepository;

public class UserUtilPackets {

    private static final Logger logger = LoggerFactory.getLogger(UserUtilPackets.class);

    @ClientPacket(ClientPackets.PING)
    public boolean ping(BanchoPacket packet, BanchoPacketReader reader, Player player) {
        player.setLastPing(System.currentTimeMillis());
        return true;
    }

    @ClientPacket(ClientPackets.LOGOUT)
    public boolean logout(BanchoPacket packet, BanchoPacketReader reader, Player player) {
        logger.info("Player {} has logged out.", player.toString());

        App.server.playerManager.disconnect(player);
        return true;
    }

    @ClientPacket(ClientPackets.FRIEND_ADD)
    public boolean addFriend(BanchoPacket packet, BanchoPacketReader reader, Player player) {
        int userId = reader.readInt();

        player.getFriends().add(userId);

        player.sendPacket(new FriendsListPacket(player.getFriends().stream().map(Integer::intValue).toList()));

        App.server.executor.execute(()-> {
            UserEntity userEntity = player.getEntity();
            UserEntity userTargetEntity = UserRepository.findById(userId);
            RelationshipRepository.addFriend(userEntity, userTargetEntity);
        });

        return true;
    }

    @ClientPacket(ClientPackets.FRIEND_REMOVE)
    public boolean removeFriend(BanchoPacket packet, BanchoPacketReader reader, Player player) {
        int userId = reader.readInt();

        player.getFriends().remove(userId);

        player.sendPacket(new FriendsListPacket(player.getFriends().stream().map(Integer::intValue).toList()));

        App.server.executor.execute(()-> {
            UserEntity userEntity = player.getEntity();
            UserEntity userTargetEntity = UserRepository.findById(userId);
            RelationshipRepository.removeFriend(userEntity, userTargetEntity);
        });

        return true;
    }

    @ClientPacket(ClientPackets.UNHANDLED_PACKET)
    public boolean handleUnhandledPacket(BanchoPacket packet, BanchoPacketReader reader, Player player) {
        logger.warn("Unhandled packet: " + reader.getCurrentPacketId() + " (" + packet.type.name() + ")");
        return true;
    }

}
