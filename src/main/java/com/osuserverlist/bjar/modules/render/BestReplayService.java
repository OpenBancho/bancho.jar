package com.osuserverlist.bjar.modules.render;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.ebean.DB;

/**
 * The front page's "best score" video: finds the highest pp osu!standard score on the server, renders its replay
 * once and keeps the mp4 on disk, one file per score.
 *
 * <p>Rendering takes tens of seconds, so it never happens inside a request: {@link #ensure} starts it on a single
 * background thread and answers {@link State#RENDERING} until the file exists. A score that failed to render is not
 * retried for a while, and the next best score is tried instead.
 */
public final class BestReplayService {

    private static final Logger logger = LoggerFactory.getLogger("BestReplayService");

    static {
        // no display on a server; must be set before anything touches AWT
        System.setProperty("java.awt.headless", "true");
    }

    public enum State {
        READY, RENDERING, UNAVAILABLE
    }

    public record Best(ScoreEntity score, BeatmapEntity beatmap) {
    }

    private static final Path DIR = Paths.get("data", "render");
    private static final long FAILED_RETRY_MS = 60 * 60 * 1000L;
    private static final long MAX_OSZ_BYTES = 250L * 1024 * 1024;
    private static final int CANDIDATES = 15;
    private static final int KEEP_VIDEOS = 3;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "replay-render");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    /**
     * Raise this whenever the look or sound of the renders changes: videos made by an older version are deleted at
     * start-up, so every best score is drawn again with the new renderer, and the site is told to fetch the new copy.
     */
    public static final int RENDER_VERSION = 6;

    static {
        try {
            Files.createDirectories(DIR);
            Path marker = DIR.resolve("version");
            String current = Files.isRegularFile(marker) ? Files.readString(marker).strip() : "";

            if (!current.equals(String.valueOf(RENDER_VERSION))) {
                try (Stream<Path> files = Files.list(DIR)) {
                    for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".mp4")).toList()) {
                        Files.deleteIfExists(p);
                    }
                }

                Files.writeString(marker, String.valueOf(RENDER_VERSION));
            }
        } catch (IOException e) {
            logger.warn("Could not check the version of the rendered videos: {}", e.getMessage());
        }
    }

    private static final Map<Long, Boolean> RENDERING = new ConcurrentHashMap<>();
    private static final Map<Long, Long> FAILED = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BestReplayService() {
    }

    public static Path videoPath(long scoreId) {
        return DIR.resolve(scoreId + ".mp4");
    }

    /**
     * The best score that can actually be shown: osu!standard, a pass, a public account, with a stored replay and a
     * beatmap the server knows. Scores that failed to render recently are skipped.
     */
    public static Best pickBest() {
        List<ScoreEntity> top = DB.find(ScoreEntity.class)
                .fetch("user", "name, country")
                .where()
                .eq("status", 2)
                .eq("mode", 0)
                .gt("pp", 0)
                .ne("grade", "F")
                // same account rule as the top scores list: restricted players never appear
                .raw("user.privileges & 3 = 3")
                .orderBy("pp desc")
                .setMaxRows(CANDIDATES)
                .findList();

        long now = System.currentTimeMillis();

        for (ScoreEntity score : top) {
            Long failedAt = FAILED.get(score.getId());

            if (failedAt != null && now - failedAt < FAILED_RETRY_MS) {
                continue;
            }

            Path replay = Paths.get("data", "replays", score.getId() + ".osr");

            try {
                if (!Files.isRegularFile(replay) || Files.size(replay) < 200) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            BeatmapEntity beatmap = BeatmapRepository.findByMd5(score.getMapMd5());

            if (beatmap == null || (beatmap.getMode() != null && beatmap.getMode() != 0)) {
                continue;
            }

            return new Best(score, beatmap);
        }

        return null;
    }

    /** Returns the state of the video for {@code best}, and starts rendering it if nobody has yet. */
    public static State ensure(Best best) {
        long id = best.score().getId();

        if (Files.isRegularFile(videoPath(id))) {
            return State.READY;
        }

        if (RENDERING.putIfAbsent(id, Boolean.TRUE) == null) {
            WORKER.submit(() -> {
                try {
                    render(best);
                    FAILED.remove(id);
                    prune(id);
                } catch (Throwable t) {
                    FAILED.put(id, System.currentTimeMillis());
                    logger.warn("Could not render the replay of score {}: {}", id, t.toString());
                } finally {
                    RENDERING.remove(id);
                }
            });
        }

        return State.RENDERING;
    }

    // ------------------------------------------------------------------ the render itself

    private static void render(Best best) throws Exception {
        ScoreEntity score = best.score();
        BeatmapEntity map = best.beatmap();
        Files.createDirectories(DIR);

        Path osz = findOsz(map.getSetId());
        byte[] osu = findOsu(map, osz);

        if (osu == null) {
            throw new IOException("beatmap file for " + map.getId() + " not found");
        }

        byte[] replay = Files.readAllBytes(Paths.get("data", "replays", score.getId() + ".osr"));

        double acc = score.getAcc() == null ? 0 : score.getAcc();

        if (acc > 1.0) {
            acc /= 100.0; // the database keeps accuracy as a percentage
        }

        int mods = score.getMods() == null ? 0 : score.getMods();
        String player = score.getUser() == null ? "unknown" : score.getUser().getName();
        String playedAt = score.getPlayTime() == null ? "" : score.getPlayTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        FrameRenderer.ScoreInfo info = new FrameRenderer.ScoreInfo(player, score.getScore() == null ? 0 : score.getScore(),
                score.getMaxCombo() == null ? 0 : score.getMaxCombo(), map.getMaxCombo() == null ? 0 : map.getMaxCombo(), acc,
                nz(score.getN300()), nz(score.getN100()), nz(score.getN50()), nz(score.getNmiss()), nz(score.getNgeki()),
                nz(score.getNkatu()), score.getGrade() == null ? "D" : score.getGrade(),
                score.getPp() == null ? 0 : score.getPp(), ModsText.of(mods), playedAt);

        logger.info("Rendering the replay of score {} ({} - {})", score.getId(), map.getTitle(), player);
        long started = System.currentTimeMillis();
        ReplayVideoRenderer.render(osu, replay, mods, osz, info, videoPath(score.getId()));
        logger.info("Score {} rendered in {} ms", score.getId(), System.currentTimeMillis() - started);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // The .osu the score was set on: the stored file when its md5 matches, else the entry of the osz that does.
    public static byte[] findOsu(BeatmapEntity map, Path osz) throws Exception {
        Path stored = Paths.get("data", "maps", map.getId() + ".osu");

        if (Files.isRegularFile(stored)) {
            byte[] data = Files.readAllBytes(stored);

            if (md5(data).equalsIgnoreCase(map.getMd5())) {
                return data;
            }
        }

        if (osz != null) {
            try (ZipFile zip = new ZipFile(osz.toFile())) {
                var entries = zip.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();

                    if (e.getName().toLowerCase().endsWith(".osu")) {
                        try (InputStream in = zip.getInputStream(e)) {
                            byte[] data = in.readAllBytes();

                            if (md5(data).equalsIgnoreCase(map.getMd5())) {
                                return data;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }

    /** The beatmap set archive: stored by the server, cached from an earlier render, or downloaded once. Null if none. */
    public static Path findOsz(long setId) {
        Path stored = Paths.get("data", "osz", setId + ".osz");

        if (Files.isRegularFile(stored)) {
            return stored;
        }

        Path cached = DIR.resolve("osz").resolve(setId + ".osz");

        if (Files.isRegularFile(cached)) {
            return cached;
        }

        String endpoint = App.server == null || App.server.enviromentConfig == null ? null : App.server.enviromentConfig.getDlEndpoint();

        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }

        try {
            Files.createDirectories(cached.getParent());
            Path partial = cached.resolveSibling(cached.getFileName() + ".part");
            HttpResponse<InputStream> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + "/" + setId))
                            .timeout(Duration.ofSeconds(120)).header("User-Agent", "bancho.jar replay renderer").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                response.body().close();
                return null;
            }

            long total = 0;

            try (InputStream in = response.body(); var out = Files.newOutputStream(partial)) {
                byte[] buf = new byte[65536];
                int n;

                while ((n = in.read(buf)) > 0) {
                    total += n;

                    if (total > MAX_OSZ_BYTES) {
                        throw new IOException("osz is too large");
                    }

                    out.write(buf, 0, n);
                }
            }

            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (Exception e) {
            logger.warn("Could not download set {} for the replay video: {}", setId, e.getMessage());
            return null;
        }
    }

    // Keeps the newest few videos; an older best score is only of use if it comes back to the top.
    private static void prune(long keepId) {
        try (Stream<Path> files = Files.list(DIR)) {
            List<Path> videos = files.filter(p -> p.getFileName().toString().endsWith(".mp4"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified())).toList();

            for (int i = KEEP_VIDEOS; i < videos.size(); i++) {
                if (!videos.get(i).equals(videoPath(keepId))) {
                    Files.deleteIfExists(videos.get(i));
                }
            }
        } catch (IOException ignored) {
            // housekeeping only
        }
    }
}
