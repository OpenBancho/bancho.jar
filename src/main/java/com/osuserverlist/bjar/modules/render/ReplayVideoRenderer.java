package com.osuserverlist.bjar.modules.render;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one replay into a looping mp4: up to {@value #MAX_PLAY_SECONDS} seconds of play followed by
 * {@value #RESULT_SECONDS} seconds of results screen, 1080p at {@value #FPS} fps.
 *
 * <p>The ffmpeg that does the encoding is the one bundled in the jar (bytedeco), so the server needs nothing
 * installed. Frames are drawn with Java2D and piped to it raw; the song, if the beatmap's osz has one, is cut to the
 * same window and muxed in.
 */
public final class ReplayVideoRenderer {

    private static final Logger logger = LoggerFactory.getLogger("ReplayVideoRenderer");

    public static final int FPS = 60;
    /** The whole play is shown; this only stops a marathon map from keeping the renderer busy for ages. */
    public static final int MAX_PLAY_SECONDS = 180;
    public static final int RESULT_SECONDS = 5;

    private ReplayVideoRenderer() {
    }

    /**
     * @param osu   the beatmap's .osu file
     * @param osr   the stored replay (raw LZMA frames or a complete .osr)
     * @param mods  mod flags of the score; only used when the replay is the bare frame stream
     * @param osz   the beatmap set archive for skin, background and audio; null renders with the bundled skin and no sound
     * @param info  what the results screen shows
     * @param out   target mp4; written to a sibling file first and moved into place when complete
     */
    public static void render(byte[] osu, byte[] osr, int mods, Path osz, FrameRenderer.ScoreInfo info, Path out) throws Exception {
        OsuMap map = OsuMap.parse(osu);
        OsrReplay replay = OsrReplay.parseStored(osr, mods);

        if (replay.mode != 0) {
            throw new IllegalArgumentException("Only osu!standard replays can be rendered");
        }

        if (map.objects.isEmpty()) {
            throw new IllegalArgumentException("Beatmap has no hit objects");
        }

        // A stored replay is only the frames: the totals the simulation is tuned against come from the score itself.
        if (replay.n300 + replay.n100 + replay.n50 + replay.nMiss == 0) {
            replay.n300 = info.n300();
            replay.n100 = info.n100();
            replay.n50 = info.n50();
            replay.nMiss = info.nMiss();
            replay.maxCombo = info.maxCombo();
        }

        PlaySim sim = new PlaySim(map, replay);
        SkinSet skin = new SkinSet(osz);
        FrameRenderer renderer = new FrameRenderer(sim, skin, skin.beatmapImage(map.backgroundFile), info);

        // The window of the play that is shown, in beatmap milliseconds.
        double first = map.objects.get(0).time;
        double lastEnd = map.objects.get(map.objects.size() - 1).endTime;
        double start = Math.max(0, first - 1500);
        renderer.setClipStart(start);
        double playMs = Math.min(MAX_PLAY_SECONDS * 1000.0 * sim.rate, lastEnd + 1500 - start);
        int playFrames = (int) Math.ceil(playMs / sim.rate / 1000.0 * FPS);
        int resultFrames = RESULT_SECONDS * FPS;
        double playSeconds = playFrames / (double) FPS;

        Path audio = extractAudio(osz, map.audioFile);

        // the hit sounds are a track of their own, mixed over the song
        Path hits = Files.createTempFile("bjar-hits-", ".wav");

        try {
            HitsoundTrack.build(sim, skin, start, playSeconds, playSeconds + RESULT_SECONDS, hits);
        } catch (Exception e) {
            logger.warn("Hit sounds could not be made: {}", e.toString());
            deleteQuietly(hits);
            hits = null;
        }

        final Path hitsFile = hits;
        Path part = out.resolveSibling(out.getFileName() + ".part");
        Files.createDirectories(out.toAbsolutePath().getParent());

        List<String> cmd = new ArrayList<>();
        cmd.add(Loader.load(org.bytedeco.ffmpeg.ffmpeg.class));
        cmd.addAll(List.of("-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr0", "-s",
                FrameRenderer.OUT_W + "x" + FrameRenderer.OUT_H, "-r", String.valueOf(FPS), "-i", "-"));

        if (audio != null) {
            cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", start / 1000.0), "-t",
                    String.format(Locale.ROOT, "%.3f", playMs / 1000.0), "-i", audio.toString()));
        }

        if (hits != null) {
            cmd.addAll(List.of("-i", hits.toString()));
        }

        cmd.addAll(List.of("-map", "0:v"));

        if (audio != null || hits != null) {
            String song = String.format(Locale.ROOT, "[1:a]atempo=%.2f,afade=t=out:st=%.2f:d=1[m]", sim.rate,
                    Math.max(0, playSeconds - 1));
            String graph = audio != null && hits != null ? song + ";[m][2:a]amix=inputs=2:duration=longest:normalize=0,apad[a]"
                    : audio != null ? song + ";[m]apad[a]" : "[1:a]apad[a]";
            cmd.addAll(List.of("-filter_complex", graph, "-map", "[a]", "-c:a", "aac", "-b:a", "128k"));
        }

        // the peak rate is capped so a busy stretch cannot make the file (or the page that plays it) balloon
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "25", "-maxrate", "14M", "-bufsize", "28M",
                "-g", String.valueOf(FPS * 2), "-pix_fmt", "yuv420p", "-movflags",
                "+faststart", "-t", String.format(Locale.ROOT, "%.3f", playSeconds + RESULT_SECONDS), "-f", "mp4",
                part.toString()));

        Process ffmpeg = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        // ffmpeg's own messages must be drained or the pipe fills up and both sides stall
        Thread drain = new Thread(() -> {
            try {
                String text = new String(ffmpeg.getInputStream().readAllBytes());

                if (!text.isBlank()) {
                    logger.warn("ffmpeg: {}", text.strip());
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "ffmpeg-log");
        drain.setDaemon(true);
        drain.start();

        int total = playFrames + resultFrames;
        final double clipStart = start;
        final int firstResultFrame = playFrames;
        final int resultCount = resultFrames;

        // Frames are drawn by several threads and written in order. Each frame is 8 MB of raw pixels, so a fixed set
        // of buffers is reused and no more frames are in flight than there are buffers.
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        int window = threads * 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "frame-render");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(window + 1);

        for (int i = 0; i < window + 1; i++) {
            free.add(new byte[FrameRenderer.OUT_W * FrameRenderer.OUT_H * 4]);
        }

        ThreadLocal<BufferedImage> canvas = ThreadLocal.withInitial(
                () -> new BufferedImage(FrameRenderer.OUT_W, FrameRenderer.OUT_H, BufferedImage.TYPE_INT_RGB));
        ArrayDeque<Future<byte[]>> pending = new ArrayDeque<>();
        int submitted = 0;

        try (OutputStream pipe = ffmpeg.getOutputStream()) {
            for (int written = 0; written < total; written++) {
                while (submitted < total && pending.size() < window) {
                    final int index = submitted++;
                    byte[] buffer = free.take();

                    pending.add(pool.submit(() -> {
                        BufferedImage frame = canvas.get();

                        if (index < firstResultFrame) {
                            renderer.renderGameplay(frame, clipStart + index * 1000.0 / FPS * sim.rate);
                        } else {
                            renderer.renderResults(frame, (index - firstResultFrame) / (double) resultCount);
                        }

                        int[] pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
                        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
                        return buffer;
                    }));
                }

                byte[] ready = pending.poll().get();
                pipe.write(ready, 0, ready.length);
                free.add(ready);
            }
        } catch (IOException | ExecutionException e) {
            ffmpeg.destroyForcibly();
            deleteQuietly(audio);
            deleteQuietly(hitsFile);
            throw new IOException("rendering the frames failed: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }

        // The audio file is still open in ffmpeg until it exits, so it can only be removed afterwards.
        boolean done = ffmpeg.waitFor(120, TimeUnit.SECONDS);

        if (!done) {
            ffmpeg.destroyForcibly();
            ffmpeg.waitFor(10, TimeUnit.SECONDS);
        }

        deleteQuietly(audio);
        deleteQuietly(hitsFile);

        if (!done || ffmpeg.exitValue() != 0) {
            deleteQuietly(part);
            throw new IOException("ffmpeg failed");
        }

        Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            file.toFile().deleteOnExit();
        }
    }

    private static Path extractAudio(Path osz, String name) {
        if (osz == null || name == null || name.isBlank()) {
            return null;
        }

        try (ZipFile zip = new ZipFile(osz.toFile())) {
            ZipEntry entry = zip.getEntry(name);

            if (entry == null) {
                var all = zip.entries();

                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();

                    if (e.getName().equalsIgnoreCase(name)) {
                        entry = e;
                        break;
                    }
                }
            }

            if (entry == null) {
                return null;
            }

            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3";
            Path tmp = Files.createTempFile("bjar-audio-", ext);

            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            return tmp;
        } catch (IOException e) {
            logger.warn("Could not read the audio out of {}: {}", osz, e.getMessage());
            return null;
        }
    }

    /** Developer entry point: {@code osu osr out [osz]}. */
    public static void main(String[] args) throws Exception {
        byte[] osu = Files.readAllBytes(Path.of(args[0]));
        byte[] osr = Files.readAllBytes(Path.of(args[1]));
        int mods = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        FrameRenderer.ScoreInfo info = new FrameRenderer.ScoreInfo("Test player", 1234567, 250, 300, 0.9876, 200, 20, 3, 1,
                50, 10, "S", 321, "", "today");
        render(osu, osr, mods, args.length > 4 ? Path.of(args[4]) : null, info, Path.of(args[2]));
    }
}
