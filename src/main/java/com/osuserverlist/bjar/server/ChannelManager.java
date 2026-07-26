package com.osuserverlist.bjar.server;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.ChannelEntity;
import com.osuserverlist.bjar.models.essentials.Channel;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.irc.IrcGateway;
import com.osuserverlist.bjar.repos.ChannelRepository;

public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void populate() {

        for (ChannelEntity entity : ChannelRepository.findAll()) {
            Channel channel = Channel.builder()
                    .id(String.valueOf(entity.getId()))
                    .name(entity.getName())
                    .alias(entity.getName())
                    .description(entity.getTopic())
                    .autoJoin(entity.getAutoJoin())
                    .readPriv(entity.getReadPriv())
                    .writePriv(entity.getWritePriv())
                    .visible(!entity.getName().equalsIgnoreCase("#lobby"))
                    .build();

            add(channel);
        }

        // Virtual channel used only for highlight notifications.
        add(Channel.builder()
                .id("highlight")
                .name("#highlight")
                .alias("#highlight")
                .description("Highlight Channel")
                .autoJoin(false)
                .readPriv(0)
                .writePriv(0)
                .visible(false)
                .build());

        logger.info("Loaded <{}> channels", channels.size());
    }

    public void add(Channel channel) {
        channels.put(channel.getName(), channel);
    }

    public Channel get(String channelName) {
        return channels.get(channelName);
    }

    public void removeChannel(String channelName) {
        channels.remove(channelName);
    }

    public Collection<Channel> getAll() {
        return channels.values();
    }

    public void joinChannel(String channelName, Player player) {
        Channel channel = channels.get(channelName);
        if (channel == null) {
            return;
        }

        channel.getPlayers().add(player);
        channel.setDirty(true);

        IrcGateway.onChannelJoin(channel, player);
    }

    public void leaveChannel(String channelName, Player player) {
        Channel channel = channels.get(channelName);
        if (channel == null) {
            return;
        }

        channel.getPlayers().remove(player);
        channel.setDirty(true);

        IrcGateway.onChannelLeave(channel, player);
    }

    public void forceJoinChannel(String channelName, Player player) {
        Channel channel = channels.get(channelName);
        if (channel != null) {
            channel.getPlayers().add(player);
            IrcGateway.onChannelJoin(channel, player);
        }
    }

    public void forceLeaveChannel(String channelName, Player player) {
        Channel channel = channels.get(channelName);
        if (channel != null) {
            channel.getPlayers().remove(player);
            IrcGateway.onChannelLeave(channel, player);
        }
    }
}
