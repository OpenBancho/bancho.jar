package com.osuserverlist.bjar.packets.server;

import java.util.List;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.essentials.ModeStats;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.packets.BanchoPacketWriter;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.PacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacket;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPacketHandler;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine.ServerPackets;

import lombok.Value;

public class UserServerPackets {

    @Value
    public static class FriendsListPacket implements ServerPacket {
        private List<Integer> friendIds;
    }

    @Value
    public static class UserPresencePacket implements ServerPacket {
        private Player player;
    }

    @Value
    public static class UserPresenceSinglePacket implements ServerPacket {
        private int userId;
    }

    @Value
    public static class UserPresenceBundlePacket implements ServerPacket {
    }

    @Value
    public static class UserStatsPacket implements ServerPacket {
        private Player player;
    }

    @Value
    public static class UserQuitPacket implements ServerPacket {
        private int userId;
    }

    @Value
    public static class AccountRestrictedPacket implements ServerPacket {
    }

    @PacketHandler(FriendsListPacket.class)
    public static final class FriendsListHandler implements ServerPacketHandler<FriendsListPacket> {
        @Override
        public void write(FriendsListPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.FRIENDS_LIST);
            writer.writeIntList(packet.getFriendIds());
            writer.endPacket();
        }
    }

    @PacketHandler(UserPresencePacket.class)
    public static final class UserPresenceHandler implements ServerPacketHandler<UserPresencePacket> {
        @Override
        public void write(UserPresencePacket packet, BanchoPacketWriter writer, Player player) {
            Player target = packet.getPlayer();
            if (target == null) return;

            writer.startPacket(ServerPackets.USER_PRESENCE);
            writer.writeInt(target.getId()); // User ID (4 bytes)
            writer.writeString(target.getUsername()); // Username (null-terminated string)
            writer.writeByte((byte) (target.getTimezone() + 24)); // Timezone (1 byte)
                                                                                                   //
            writer.writeByte((byte) target.getCountry()); // Country ID (1 byte)
            writer.writeByte((byte) (target.getClientPrivileges() | (target.getGameMode()) << 5)); // Permissions | Mode << 5 (1 byte)
            writer.writeFloat(target.getLongitude()); // Longitude (4 bytes)
            writer.writeFloat(target.getLatitude()); // Latitude (4 bytes)
            writer.writeInt(target.getRank()); // Rank (4 bytes)
            writer.endPacket();
        }
    }

    @PacketHandler(UserPresenceSinglePacket.class)
    public static  final class UserPresenceSingleHandler implements ServerPacketHandler<UserPresenceSinglePacket> {
        @Override
        public void write(UserPresenceSinglePacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.USER_PRESENCE_SINGLE);
            writer.writeInt(packet.getUserId());
            writer.endPacket();
        }
    }

    @PacketHandler(UserPresenceBundlePacket.class)
    public static  final class UserPresenceBundleHandler implements ServerPacketHandler<UserPresenceBundlePacket> {
        @Override
        public void write(UserPresenceBundlePacket packet, BanchoPacketWriter writer, Player player) {
            List<Integer> userIds = App.server.playerManager.getAllSessions().stream()
                    .filter(p -> p.getId() != player.getId())
                    .map(Player::getId)
                    .toList();
            writer.startPacket(ServerPackets.USER_PRESENCE_BUNDLE);
            writer.writeIntList(userIds);
            writer.endPacket();
        }
    }

    @PacketHandler(UserStatsPacket.class)
    public static final class UserStatsHandler implements ServerPacketHandler<UserStatsPacket> {
        @Override
        public void write(UserStatsPacket packet, BanchoPacketWriter writer, Player player) {
            Player target = packet.getPlayer();
            ModeStats playerStats = target.getModeStats()[target.getRealGameMode()];
            String beatmapMd5 = target.getBeatmapMd5() != null ? target.getBeatmapMd5() : "";
            float accuracy = playerStats.getAccuracy();
            if (accuracy > 1.0f) {
                accuracy /= 100.0f;
            }

            int pp = 0;
            long rankedScore = 0;
            if (playerStats.getPp() > Short.MAX_VALUE) {
                rankedScore = (int) playerStats.getPp();
            } else {
                pp = (int) playerStats.getPp();
                rankedScore = playerStats.getRankedScore();
            }

            writer.startPacket(ServerPackets.USER_STATS);
            writer.writeInt(target.getId());
            writer.writeByte((target.getAction() & 0xFF));
            writer.writeString(target.getActionText());
            writer.writeString(beatmapMd5);
            writer.writeInt(target.getMods());
            writer.writeByte(target.getGameMode());
            writer.writeInt(target.getBeatmapId());
            writer.writeLong(rankedScore);
            writer.writeFloat(accuracy);
            writer.writeInt(playerStats.getPlayCount());
            writer.writeLong(playerStats.getTotalScore());
            Long redisRank = Redis.getClient().zrevrank("bjar:leaderboard:" + target.getRealGameMode(),
                    String.valueOf(target.getId()));
            writer.writeInt((redisRank != null ? Math.toIntExact(redisRank) : -1) + 1);
            writer.writeShort((short) Math.ceil(pp));
            writer.endPacket();
        }
    }

    @PacketHandler(UserQuitPacket.class)
    public static final class UserQuitHandler implements ServerPacketHandler<UserQuitPacket> {
        @Override
        public void write(UserQuitPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.USER_LOGOUT);
            writer.writeInt(packet.getUserId());
            writer.endPacket();
        }
    }

    @PacketHandler(AccountRestrictedPacket.class)
    public static final class AccountRestrictedHandler implements ServerPacketHandler<AccountRestrictedPacket> {
        @Override
        public void write(AccountRestrictedPacket packet, BanchoPacketWriter writer, Player player) {
            writer.startPacket(ServerPackets.ACCOUNT_RESTRICTED);
            writer.endPacket();
        }
    }

}
