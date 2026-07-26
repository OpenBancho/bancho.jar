package com.osuserverlist.bjar.modules.osu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class OsuMapDownloader {

    private static final Path MAP_DIRECTORY = Path.of("data", "maps");
    private static final Logger logger = LoggerFactory.getLogger(OsuMapDownloader.class);

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    public static byte[] downloadMap(long mapId) {
        Path mapFile = MAP_DIRECTORY.resolve(mapId + ".osu");

        try {
            if (Files.exists(mapFile)) {
                return Files.readAllBytes(mapFile);
            }

            // Beatmaps submitted through the BSS only exist on this server.
            // Asking osu.ppy.sh for them would always 404, so the stored package
            // is the only place left to look.
            if (BeatmapSubmissionService.isLocalId(mapId)) {
                byte[] recovered = BeatmapSubmissionService.restoreBeatmapFile(mapId);

                if (recovered != null) {
                    logger.info("Restored locally submitted map <{}> from its package", mapId);
                    return recovered;
                }

                logger.warn("Locally submitted map <{}> is missing from disk and from its package",
                        mapId);
                return null;
            }

            Request request = new Request.Builder()
                    .url("https://osu.ppy.sh/osu/" + mapId)
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }

                byte[] data = response.body().bytes();

                scheduleDiskCache(mapFile, data, mapId);

                return data;
            }
        } catch (IOException e) {
            logger.error("Failed to download map with ID: " + mapId, e);
            return null;
        }
    }

    private static void scheduleDiskCache(Path mapFile, byte[] data, long mapId) {
        App.server.executor.submit(() -> {
            try {
                Files.write(mapFile, data);
            } catch (IOException e) {
                logger.error("Failed to cache downloaded map to disk for ID: " + mapId, e);
            }
        });
    }
}