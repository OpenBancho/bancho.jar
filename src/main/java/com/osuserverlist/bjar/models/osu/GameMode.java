package com.osuserverlist.bjar.models.osu;

public enum GameMode {
    VANILLA_OSU(0),
    VANILLA_TAIKO(1),
    VANILLA_CATCH(2),
    VANILLA_MANIA(3),

    RELAX_OSU(4),
    RELAX_TAIKO(5),
    RELAX_CATCH(6),
    RELAX_MANIA(7),

    AUTOPILOT_OSU(8),
    AUTOPILOT_TAIKO(9),
    AUTOPILOT_CATCH(10),
    AUTOPILOT_MANIA(11);

    private final int value;

    GameMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static GameMode fromValue(int modeVn, int mods) {
        int mode = modeVn;

        boolean hasRelax2 = (mods & Mods.Relax2.getValue()) != 0;
        boolean hasRelax  = (mods & Mods.Relax.getValue()) != 0;

        if (hasRelax2 && modeVn == 0) {
            // Autopilot only exists for osu!standard
            mode += 8;
        } else if (hasRelax) {
            // Relax exists for all 4 modes
            mode += 4;
        }

        for (GameMode gm : GameMode.values()) {
            if (gm.getValue() == mode) {
                return gm;
            }
        }

        throw new IllegalArgumentException("Invalid game mode value: " + mode);
    }

    public static GameMode fromValue(int value) {
        for (GameMode gm : GameMode.values()) {
            if (gm.getValue() == value) {
                return gm;
            }
        }
        throw new IllegalArgumentException("Invalid game mode value: " + value);
    }

    public static GameMode toVanillaMode(GameMode mode) {
        switch (mode) {
            case VANILLA_OSU:
            case RELAX_OSU:
            case AUTOPILOT_OSU:
                return VANILLA_OSU;
            case VANILLA_TAIKO:
            case RELAX_TAIKO:
            case AUTOPILOT_TAIKO:
                return VANILLA_TAIKO;
            case VANILLA_CATCH:
            case RELAX_CATCH:
            case AUTOPILOT_CATCH:
                return VANILLA_CATCH;
            case VANILLA_MANIA:
            case RELAX_MANIA:
            case AUTOPILOT_MANIA:
                return VANILLA_MANIA;
            default:
                throw new IllegalArgumentException("Invalid game mode: " + mode);
        }
    }

}
