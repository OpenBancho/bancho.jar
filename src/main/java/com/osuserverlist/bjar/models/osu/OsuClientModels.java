package com.osuserverlist.bjar.models.osu;

import java.util.Arrays;

import lombok.AllArgsConstructor;

public class OsuClientModels {

    @AllArgsConstructor
    public static enum PresenceFilter {
        NONE(0),
        ALL(1),
        FRIENDS(2);

        public final int id;
    }

    @AllArgsConstructor
    public static enum ActionStatus {
        EDITING(3),
        WATCHING(6),
        TESTING(8),
        SUBMITTING(9);

        public final int id;

        public static ActionStatus getById(int id) {
            return Arrays.stream(values())
                    .filter(s -> s.id == id)
                    .findFirst()
                    .orElse(null);
        }
    }

    @AllArgsConstructor
    public enum LeaderboardType {
        GLOBAL(1),
        GLOBAL_MODS(2),
        FRIENDS(3),
        COUNTRY(4);

        public final int id;

        public static LeaderboardType getById(int id) {
            for (LeaderboardType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return GLOBAL; // Default to global if unknown type received
        }
    }

}
