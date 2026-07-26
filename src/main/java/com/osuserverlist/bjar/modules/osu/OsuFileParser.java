package com.osuserverlist.bjar.modules.osu;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import lombok.Data;

/**
 * Minimal parser for the {@code .osu} file format.
 *
 * <p>osz2.jar deliberately does not provide beatmap parsing, so the submission
 * system needs its own reader to be able to fill the {@code maps} table.
 * Only the fields that bancho.jar actually persists are extracted.</p>
 */
public final class OsuFileParser {

    private OsuFileParser() {
    }

    @Data
    public static class ParsedBeatmap {
        private long beatmapId = 0;
        private long beatmapSetId = 0;

        private String artist = "";
        private String artistUnicode = "";
        private String title = "";
        private String titleUnicode = "";
        private String creator = "";
        private String version = "";
        private String source = "";
        private String tags = "";

        private int mode = 0;
        private int previewTime = -1;

        private float hp = 5f;
        private float cs = 5f;
        private float od = 5f;
        private float ar = 5f;

        private float bpm = 0f;
        private int totalLength = 0;
        private int drainLength = 0;
        private int maxCombo = 0;
        private int objectCount = 0;

        private boolean hasVideo = false;
    }

    /**
     * Parses the given {@code .osu} file contents.
     *
     * @param content raw file bytes.
     * @return the parsed beatmap, never {@code null}. Unknown fields keep
     *         their defaults so a slightly malformed file never breaks a
     *         submission.
     */
    public static ParsedBeatmap parse(byte[] content) {
        ParsedBeatmap map = new ParsedBeatmap();

        String text = new String(content, StandardCharsets.UTF_8);
        String section = "";

        // beatLength (ms) -> total duration the timing point is active for.
        Map<Double, Double> bpmDurations = new HashMap<>();

        double lastUninheritedTime = -1;
        double lastUninheritedBeatLength = 0;

        int firstObjectTime = Integer.MAX_VALUE;
        int lastObjectTime = 0;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();

            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }

            switch (section) {
                case "General", "Metadata", "Difficulty" -> parseKeyValue(map, line);
                case "Events" -> parseEvent(map, line);
                case "TimingPoints" -> {
                    String[] parts = line.split(",");

                    if (parts.length < 2) {
                        continue;
                    }

                    double time = parseDouble(parts[0], 0);
                    double beatLength = parseDouble(parts[1], 0);

                    if (beatLength <= 0) {
                        // Inherited timing point, does not affect BPM.
                        continue;
                    }

                    if (lastUninheritedTime >= 0) {
                        bpmDurations.merge(
                                lastUninheritedBeatLength,
                                Math.max(0, time - lastUninheritedTime),
                                Double::sum);
                    }

                    lastUninheritedTime = time;
                    lastUninheritedBeatLength = beatLength;
                }
                case "HitObjects" -> {
                    String[] parts = line.split(",");

                    if (parts.length < 4) {
                        continue;
                    }

                    int time = (int) parseDouble(parts[2], 0);
                    int type = (int) parseDouble(parts[3], 0);

                    int endTime = time;

                    boolean isSpinner = (type & 8) != 0;
                    boolean isHold = (type & 128) != 0;
                    boolean isSlider = (type & 2) != 0;

                    if (isSpinner && parts.length >= 6) {
                        endTime = (int) parseDouble(parts[5], time);
                    } else if (isHold && parts.length >= 6) {
                        // osu!mania hold note: endTime is the first ':'-separated value.
                        endTime = (int) parseDouble(parts[5].split(":")[0], time);
                    }

                    firstObjectTime = Math.min(firstObjectTime, time);
                    lastObjectTime = Math.max(lastObjectTime, endTime);

                    map.objectCount++;

                    // Approximation: sliders award at least a head and a tail
                    // combo. Slider ticks require full slider-path evaluation
                    // and are intentionally not simulated here.
                    map.maxCombo += isSlider ? 2 : 1;
                }
                default -> {
                    // Sections we do not care about (Editor, Colours, ...).
                }
            }
        }

        if (lastUninheritedTime >= 0) {
            bpmDurations.merge(
                    lastUninheritedBeatLength,
                    Math.max(1, lastObjectTime - lastUninheritedTime),
                    Double::sum);
        }

        map.bpm = (float) resolveDominantBpm(bpmDurations);

        if (map.objectCount > 0) {
            map.totalLength = Math.max(0, lastObjectTime / 1000);
            map.drainLength = Math.max(0, (lastObjectTime - firstObjectTime) / 1000);
        }

        // osu!std files written before AR existed fall back to OD.
        if (map.ar == 5f && map.od != 5f && !text.contains("ApproachRate")) {
            map.ar = map.od;
        }

        return map;
    }

    private static void parseKeyValue(ParsedBeatmap map, String line) {
        int separator = line.indexOf(':');

        if (separator < 0) {
            return;
        }

        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();

        switch (key) {
            case "Mode" -> map.mode = (int) parseDouble(value, 0);
            case "PreviewTime" -> map.previewTime = (int) parseDouble(value, -1);

            case "Title" -> map.title = value;
            case "TitleUnicode" -> map.titleUnicode = value;
            case "Artist" -> map.artist = value;
            case "ArtistUnicode" -> map.artistUnicode = value;
            case "Creator" -> map.creator = value;
            case "Version" -> map.version = value;
            case "Source" -> map.source = value;
            case "Tags" -> map.tags = value;
            case "BeatmapID" -> map.beatmapId = (long) parseDouble(value, 0);
            case "BeatmapSetID" -> map.beatmapSetId = (long) parseDouble(value, 0);

            case "HPDrainRate" -> map.hp = (float) parseDouble(value, 5);
            case "CircleSize" -> map.cs = (float) parseDouble(value, 5);
            case "OverallDifficulty" -> map.od = (float) parseDouble(value, 5);
            case "ApproachRate" -> map.ar = (float) parseDouble(value, 5);

            default -> {
                // Ignored key.
            }
        }
    }

    private static void parseEvent(ParsedBeatmap map, String line) {
        // Video events look like: "Video,0,\"bg.mp4\"" or "1,0,\"bg.avi\"".
        String[] parts = line.split(",");

        if (parts.length < 3) {
            return;
        }

        String type = parts[0].trim();

        if (type.equalsIgnoreCase("Video") || type.equals("1")) {
            map.hasVideo = true;
        }
    }

    private static double resolveDominantBpm(Map<Double, Double> bpmDurations) {
        double bestBeatLength = 0;
        double bestDuration = -1;

        for (Map.Entry<Double, Double> entry : bpmDurations.entrySet()) {
            if (entry.getValue() > bestDuration) {
                bestDuration = entry.getValue();
                bestBeatLength = entry.getKey();
            }
        }

        if (bestBeatLength <= 0) {
            return 0;
        }

        return 60000d / bestBeatLength;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }
}
