package com.osuserverlist.bjar.modules.render;

/** Turns the mod bit flags of a score into the usual two-letter list, "HDDT". */
final class ModsText {

    private ModsText() {
    }

    static String of(int mods) {
        StringBuilder sb = new StringBuilder();

        append(sb, mods, 1, "NF");
        append(sb, mods, 2, "EZ");
        append(sb, mods, 4, "TD");
        append(sb, mods, 8, "HD");
        append(sb, mods, 16, "HR");

        // PF implies SD and NC implies DT: only the stronger one is shown
        if ((mods & 16384) != 0) {
            sb.append("PF");
        } else {
            append(sb, mods, 32, "SD");
        }

        if ((mods & 512) != 0) {
            sb.append("NC");
        } else {
            append(sb, mods, 64, "DT");
        }

        append(sb, mods, 128, "RX");
        append(sb, mods, 256, "HT");
        append(sb, mods, 1024, "FL");
        append(sb, mods, 4096, "SO");
        append(sb, mods, 8192, "AP");

        return sb.toString();
    }

    /** Names of the skin's {@code selection-mod-*} pictures for the mods of a score, in display order. */
    static java.util.List<String> iconNames(int mods) {
        java.util.List<String> out = new java.util.ArrayList<>();

        if ((mods & 1) != 0) out.add("nofail");
        if ((mods & 2) != 0) out.add("easy");
        if ((mods & 8) != 0) out.add("hidden");
        if ((mods & 16) != 0) out.add("hardrock");

        if ((mods & 16384) != 0) {
            out.add("perfect");
        } else if ((mods & 32) != 0) {
            out.add("suddendeath");
        }

        if ((mods & 512) != 0) {
            out.add("nightcore");
        } else if ((mods & 64) != 0) {
            out.add("doubletime");
        }

        if ((mods & 128) != 0) out.add("relax");
        if ((mods & 256) != 0) out.add("halftime");
        if ((mods & 1024) != 0) out.add("flashlight");
        if ((mods & 4096) != 0) out.add("spunout");
        if ((mods & 8192) != 0) out.add("relax2");

        return out;
    }

    private static void append(StringBuilder sb, int mods, int bit, String name) {
        if ((mods & bit) != 0) {
            sb.append(name);
        }
    }
}
