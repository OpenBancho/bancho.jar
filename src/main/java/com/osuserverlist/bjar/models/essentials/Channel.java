package com.osuserverlist.bjar.models.essentials;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Channel {
    private String id;
    private String name;
    private String alias;
    private String description;
    private boolean autoJoin;
    private final Set<Player> players = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty;
    @Builder.Default
    private int readPriv = 1;
    @Builder.Default
    private int writePriv = 1;
    @Builder.Default
    private boolean visible = true;

    public int getPlayerCount() {
        return players.size();
    }
}
