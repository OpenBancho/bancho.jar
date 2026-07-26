package com.osuserverlist.bjar.models.osu;

public final class AntiCheatFlags {

    public static final class ClientFlags {
        /** No flags sent. */
        public static final int CLEAN = 0;

        // Timing errors / desync
        public static final int SPEED_HACK_DETECTED = 1 << 1;

        // Ignore (official client trolling)
        public static final int INCORRECT_MOD_VALUE = 1 << 2;

        public static final int MULTIPLE_OSU_CLIENTS = 1 << 3;
        public static final int CHECKSUM_FAILURE = 1 << 4;
        public static final int FLASHLIGHT_CHECKSUM_INCORRECT = 1 << 5;

        // Official Bancho only
        public static final int OSU_EXECUTABLE_CHECKSUM = 1 << 6;
        public static final int MISSING_PROCESSES_IN_LIST = 1 << 7;

        // Flashlight hacks
        public static final int FLASHLIGHT_IMAGE_HACK = 1 << 8;

        public static final int SPINNER_HACK = 1 << 9;
        public static final int TRANSPARENT_WINDOW = 1 << 10;

        // osu!mania
        public static final int FAST_PRESS = 1 << 11;

        // Good indicators for autobotting
        public static final int RAW_MOUSE_DISCREPANCY = 1 << 12;
        public static final int RAW_KEYBOARD_DISCREPANCY = 1 << 13;
    }

    public static final class LastFMFlags {
        public static final int RUN_WITH_LD_FLAG = 1 << 14;
        public static final int CONSOLE_OPEN = 1 << 15;
        public static final int EXTRA_THREADS = 1 << 16;
        public static final int HQ_ASSEMBLY = 1 << 17;
        public static final int HQ_FILE = 1 << 18;
        public static final int REGISTRY_EDITS = 1 << 19;
        public static final int SDL2_LIBRARY = 1 << 20;
        public static final int OPENSSL_LIBRARY = 1 << 21;
        public static final int AQN_MENU_SAMPLE = 1 << 22;
    }

    public static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
