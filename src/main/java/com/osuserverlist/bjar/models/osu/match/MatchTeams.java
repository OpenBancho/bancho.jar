package com.osuserverlist.bjar.models.osu.match;

public enum MatchTeams {
    NEUTRAL(0),
    BLUE(1),
    RED(2);

    public final int value;
    public final byte byteValue;

    MatchTeams(int value) {
        this.value = value;
        this.byteValue = (byte) value;
    }

}
