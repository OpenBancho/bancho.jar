package com.osuserverlist.bjar.modules.osu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk storage layout of the Beatmap Submission System.
 *
 * <pre>
 * data/osz2/{setId}.osz2          encrypted package, source of truth
 * data/osz2/{setId}.base.osz2     last bytes the client uploaded, patch base
 * data/osz/{setId}.osz            plain package served to osu!direct (with video)
 * data/osz/{setId}n.osz           plain package without video files
 * data/maps/{beatmapId}.osu       individual difficulties (shared with OsuMapDownloader)
 * </pre>
 */
public final class BssStorage {

    private static final Logger logger = LoggerFactory.getLogger(BssStorage.class);

    private static final Path OSZ2_DIRECTORY = Path.of("data", "osz2");
    private static final Path OSZ_DIRECTORY = Path.of("data", "osz");
    private static final Path MAP_DIRECTORY = Path.of("data", "maps");

    private BssStorage() {
    }

    public static void initialize() {
        try {
            Files.createDirectories(OSZ2_DIRECTORY);
            Files.createDirectories(OSZ_DIRECTORY);
            Files.createDirectories(MAP_DIRECTORY);
        } catch (IOException e) {
            logger.error("Failed to initialize BSS storage directories", e);
        }
    }

    public static Path osz2Path(int setId) {
        return OSZ2_DIRECTORY.resolve(setId + ".osz2");
    }

    /**
     * The exact package the client last sent us.
     *
     * <p>Kept next to the canonical copy because incremental uploads are byte
     * diffs against what the client itself holds, and the canonical copy is a
     * re-export whose bytes differ.</p>
     */
    public static Path patchBasePath(int setId) {
        return OSZ2_DIRECTORY.resolve(setId + ".base.osz2");
    }

    public static Path oszPath(int setId, boolean noVideo) {
        return OSZ_DIRECTORY.resolve(setId + (noVideo ? "n" : "") + ".osz");
    }

    public static Path beatmapPath(long beatmapId) {
        return MAP_DIRECTORY.resolve(beatmapId + ".osu");
    }

    public static boolean hasOsz2(int setId) {
        return Files.exists(osz2Path(setId));
    }

    public static boolean hasPatchBase(int setId) {
        return Files.exists(patchBasePath(setId));
    }

    public static boolean hasOsz(int setId, boolean noVideo) {
        return Files.exists(oszPath(setId, noVideo));
    }

    public static byte[] readOsz2(int setId) throws IOException {
        return Files.readAllBytes(osz2Path(setId));
    }

    public static byte[] readPatchBase(int setId) throws IOException {
        return Files.readAllBytes(patchBasePath(setId));
    }

    public static byte[] readOsz(int setId, boolean noVideo) throws IOException {
        return Files.readAllBytes(oszPath(setId, noVideo));
    }

    public static void writeOsz2(int setId, byte[] data) throws IOException {
        writeAtomically(osz2Path(setId), data);
    }

    public static void writePatchBase(int setId, byte[] data) throws IOException {
        writeAtomically(patchBasePath(setId), data);
    }

    public static void writeOsz(int setId, boolean noVideo, byte[] data) throws IOException {
        writeAtomically(oszPath(setId, noVideo), data);
    }

    public static void writeBeatmap(long beatmapId, byte[] data) throws IOException {
        writeAtomically(beatmapPath(beatmapId), data);
    }

    public static void deleteBeatmap(long beatmapId) {
        try {
            Files.deleteIfExists(beatmapPath(beatmapId));
        } catch (IOException e) {
            logger.warn("Failed to delete cached beatmap file for id <{}>", beatmapId, e);
        }
    }

    public static void deleteSet(int setId) {
        try {
            Files.deleteIfExists(osz2Path(setId));
            Files.deleteIfExists(patchBasePath(setId));
            Files.deleteIfExists(oszPath(setId, false));
            Files.deleteIfExists(oszPath(setId, true));
        } catch (IOException e) {
            logger.warn("Failed to delete stored packages for set <{}>", setId, e);
        }
    }

    /**
     * Writes to a temporary file first and then moves it into place, so a
     * crash mid-upload can never leave a truncated package that would be
     * served to players.
     */
    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());

        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        Files.write(temp, data);

        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some filesystems do not support atomic moves.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
