package com.osuserverlist.bjar.models.osu;

import java.util.ArrayList;

public enum Mods {
    None(0),
    NoFail(1),
    Easy(2),
    TouchDevice(4),
    Hidden(8),
    HardRock(16),
    SuddenDeath(32),
    DoubleTime(64),
    Relax(128),
    HalfTime(256),
    Nightcore(512), // Only set along with DoubleTime. i.e: NC only gives 576
    Flashlight(1024),
    Autoplay(2048),
    SpunOut(4096),
    Relax2(8192), // Autopilot
    Perfect(16384), // Only set along with SuddenDeath. i.e: PF only gives 16416
    Key4(32768),
    Key5(65536),
    Key6(131072),
    Key7(262144),
    Key8(524288),
    FadeIn(1048576),
    Random(2097152),
    Cinema(4194304),
    Target(8388608),
    Key9(16777216),
    KeyCoop(33554432),
    Key1(67108864),
    Key3(134217728),
    Key2(268435456),
    ScoreV2(536870912),
    Mirror(1073741824);

    public static final int SPEED_CHANGING_MODS = Mods.DoubleTime.getValue()
                                                 | Mods.Nightcore.getValue()
                                                 | Mods.HalfTime.getValue();

    private final int value;

    Mods(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    private static final int[] MOD_VALUES = {
            Mods.NoFail.getValue(),
            Mods.Easy.getValue(),
            Mods.TouchDevice.getValue(),
            Mods.Hidden.getValue(),
            Mods.HardRock.getValue(),
            Mods.SuddenDeath.getValue(),
            Mods.DoubleTime.getValue(),
            Mods.Relax.getValue(),
            Mods.HalfTime.getValue(),
            Mods.Nightcore.getValue(),
            Mods.Flashlight.getValue(),
            Mods.Autoplay.getValue(),
            Mods.SpunOut.getValue(),
            Mods.Relax2.getValue(),
            Mods.Perfect.getValue(),
            Mods.Key4.getValue(),
            Mods.Key5.getValue(),
            Mods.Key6.getValue(),
            Mods.Key7.getValue(),
            Mods.Key8.getValue(),
            Mods.FadeIn.getValue(),
            Mods.Random.getValue(),
            Mods.Cinema.getValue(),
            Mods.Target.getValue(),
            Mods.Key9.getValue(),
            Mods.KeyCoop.getValue(),
            Mods.Key1.getValue(),
            Mods.Key3.getValue(),
            Mods.Key2.getValue(),
            Mods.ScoreV2.getValue(),
            Mods.Mirror.getValue()
    };

    private static final String[] MOD_NAMES = {
            "NF", "EZ", "TD", "HD", "HR",
            "SD", "DT", "RX", "HT", "NC",
            "FL", "AT", "SO", "AP", "PF",
            "4K", "5K", "6K", "7K", "8K",
            "FI", "RD", "CN", "TP", "9K",
            "KC", "1K", "3K", "2K", "SV2",
            "MR"
    };

    public static Mods fromAbbreviation(String abbreviation) {
        for (int i = 0; i < MOD_NAMES.length; i++) {
            if (MOD_NAMES[i].equalsIgnoreCase(abbreviation)) {
                return Mods.values()[i + 1]; // +1 because Mods.None is at index 0
            }
        }
        throw new IllegalArgumentException("Invalid mod abbreviation: " + abbreviation);
    }

    public static String[] convertMods(int mods) {
        ArrayList<String> result = new ArrayList<>(8);

        boolean hasNC = (mods & Mods.Nightcore.getValue()) != 0;
        boolean hasPF = (mods & Mods.Perfect.getValue()) != 0;

        for (int i = 0; i < MOD_VALUES.length; i++) {
            int modValue = MOD_VALUES[i];

            // NC implies DT
            if (hasNC && modValue == Mods.DoubleTime.getValue()) {
                continue;
            }

            // PF implies SD
            if (hasPF && modValue == Mods.SuddenDeath.getValue()) {
                continue;
            }

            if ((mods & modValue) != 0) {
                result.add(MOD_NAMES[i]);
            }
        }

        return result.toArray(String[]::new);
    }

    public static Mods[] fromBitmask(int mods) {
        ArrayList<Mods> result = new ArrayList<>(8);

        for (Mods mod : Mods.values()) {
            if ((mods & mod.getValue()) != 0) {
                result.add(mod);
            }
        }

        return result.toArray(Mods[]::new);
    }

    public static boolean hasMod(int mods, Mods mod) {
        return (mods & mod.getValue()) != 0;
    }
}
