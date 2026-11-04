package com.osuserverlist.bjar.modules.render;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An osu!standard beatmap, reduced to what the replay renderer draws: difficulty, timing, hit objects (with slider
 * paths already flattened to polylines), combo colours and the background file name.
 */
public final class OsuMap {

    public static final int CIRCLE = 0;
    public static final int SLIDER = 1;
    public static final int SPINNER = 2;

    public static final class Obj {
        public int type;
        public double x, y;
        public double time;
        public double endTime;
        public boolean newCombo;
        /** Number drawn on the circle, restarting at every new combo. */
        public int comboNumber;
        public int comboIndex;

        // sliders
        public int slides = 1;
        public double pixelLength;
        public double spanDuration;
        public double[] px = new double[0];
        public double[] py = new double[0];
        /** Cumulative length of every polyline vertex, same size as px. */
        public double[] dist = new double[0];
        /** Times (beatmap ms) of slider ticks along the first span, as fractions of the span. */
        public List<Double> tickFractions = new ArrayList<>();

        // hit sounds: bit 1 normal, 2 whistle, 4 finish, 8 clap; sample sets 0 = inherit, 1 normal, 2 soft, 3 drum
        public int hitSound;
        public int normalSet, additionSet, sampleIndex, sampleVolume;
        /** Sliders: one entry per edge (head, repeats, tail): sound bits and {normalSet, additionSet}. */
        public int[] edgeSounds = new int[0];
        public int[][] edgeSets = new int[0][];

        /** Position of the slider ball at {@code progress} in [0, slides]. */
        public double[] ballAt(double progress) {
            double span = Math.min(slides, Math.max(0, progress));
            int n = (int) Math.floor(span);
            double frac = span - n;

            if (n >= slides) {
                n = slides - 1;
                frac = 1;
            }

            if ((n & 1) == 1) {
                frac = 1 - frac;
            }

            return pointAtFraction(frac);
        }

        public double[] pointAtFraction(double frac) {
            if (px.length == 0) {
                return new double[] { x, y };
            }

            double target = frac * dist[dist.length - 1];
            int lo = 0;
            int hi = dist.length - 1;

            while (lo < hi) {
                int mid = (lo + hi) >>> 1;

                if (dist[mid] < target) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }

            if (lo == 0) {
                return new double[] { px[0], py[0] };
            }

            double seg = dist[lo] - dist[lo - 1];
            double t = seg <= 0 ? 0 : (target - dist[lo - 1]) / seg;
            return new double[] { px[lo - 1] + (px[lo] - px[lo - 1]) * t, py[lo - 1] + (py[lo] - py[lo - 1]) * t };
        }
    }

    public String artist = "", title = "", version = "", creator = "";
    public String audioFile = "";
    public String backgroundFile = "";
    public double hp = 5, cs = 5, od = 5, ar = 5;
    public double sliderMultiplier = 1.4, sliderTickRate = 1;
    public double stackLeniency = 0.7;
    public final List<Obj> objects = new ArrayList<>();
    public final List<Color> comboColors = new ArrayList<>();
    /** True when the beatmap defines its own combo colours; otherwise the skin's are used. */
    public boolean ownColors;
    /** Break periods {start, end} in beatmap ms. */
    public final List<double[]> breaks = new ArrayList<>();
    /** Sample set the beatmap falls back on: 1 normal, 2 soft, 3 drum. */
    public int defaultSampleSet = 1;
    private final List<double[]> samplePoints = new ArrayList<>(); // time, set, index, volume

    private final List<double[]> uninherited = new ArrayList<>(); // time, beatLength
    private final List<double[]> inherited = new ArrayList<>(); // time, slider velocity multiplier

    public static OsuMap parse(byte[] data) {
        OsuMap m = new OsuMap();
        String section = "";

        for (String raw : new String(data, StandardCharsets.UTF_8).split("\\r?\\n")) {
            String line = raw.strip();

            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }

            try {
                switch (section) {
                    case "General" -> m.general(line);
                    case "Metadata" -> m.metadata(line);
                    case "Difficulty" -> m.difficulty(line);
                    case "Events" -> m.event(line);
                    case "TimingPoints" -> m.timing(line);
                    case "Colours" -> m.colour(line);
                    case "HitObjects" -> m.hitObject(line);
                    default -> {
                    }
                }
            } catch (RuntimeException ignored) {
                // one malformed line must not lose the whole map
            }
        }

        m.objects.sort((a, b) -> Double.compare(a.time, b.time));
        m.finish();
        return m;
    }

    private static String[] kv(String line) {
        int i = line.indexOf(':');
        return i < 0 ? new String[] { line, "" } : new String[] { line.substring(0, i).strip(), line.substring(i + 1).strip() };
    }

    private void general(String line) {
        String[] p = kv(line);

        if (p[0].equals("AudioFilename")) {
            audioFile = p[1];
        } else if (p[0].equals("StackLeniency")) {
            stackLeniency = Double.parseDouble(p[1]);
        } else if (p[0].equals("SampleSet")) {
            defaultSampleSet = switch (p[1].toLowerCase()) {
                case "soft" -> 2;
                case "drum" -> 3;
                default -> 1;
            };
        }
    }

    private void metadata(String line) {
        String[] p = kv(line);

        switch (p[0]) {
            case "Title" -> title = p[1];
            case "Artist" -> artist = p[1];
            case "Creator" -> creator = p[1];
            case "Version" -> version = p[1];
            default -> {
            }
        }
    }

    private void difficulty(String line) {
        String[] p = kv(line);
        double v = Double.parseDouble(p[1]);

        switch (p[0]) {
            case "HPDrainRate" -> hp = v;
            case "CircleSize" -> cs = v;
            case "OverallDifficulty" -> od = v;
            case "ApproachRate" -> ar = v;
            case "SliderMultiplier" -> sliderMultiplier = v;
            case "SliderTickRate" -> sliderTickRate = v;
            default -> {
            }
        }
    }

    private void event(String line) {
        String[] p = line.split(",");

        if (p.length >= 3 && (p[0].equals("2") || p[0].equalsIgnoreCase("Break"))) {
            breaks.add(new double[] { Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip()) });
        }

        if (p.length >= 3 && p[0].equals("0") && p[1].equals("0") && backgroundFile.isEmpty()) {
            backgroundFile = p[2].replace("\"", "").strip();
        }
    }

    private void timing(String line) {
        String[] p = line.split(",");
        double time = Double.parseDouble(p[0]);
        double beatLength = Double.parseDouble(p[1]);
        boolean isUninherited = p.length < 7 || p[6].strip().equals("1");

        if (p.length >= 6) {
            samplePoints.add(new double[] { time, Integer.parseInt(p[3].strip()), Integer.parseInt(p[4].strip()),
                    Integer.parseInt(p[5].strip()) });
        }

        if (isUninherited) {
            uninherited.add(new double[] { time, beatLength });
        } else {
            double mult = beatLength < 0 ? Math.min(10, Math.max(0.1, -100.0 / beatLength)) : 1;
            inherited.add(new double[] { time, mult });
        }
    }

    private void colour(String line) {
        String[] p = kv(line);

        if (!p[0].startsWith("Combo")) {
            return;
        }

        String[] c = p[1].split(",");
        comboColors.add(new Color(Integer.parseInt(c[0].strip()), Integer.parseInt(c[1].strip()), Integer.parseInt(c[2].strip())));
        ownColors = true;
    }

    private double beatLengthAt(double t) {
        double best = uninherited.isEmpty() ? 500 : uninherited.get(0)[1];

        for (double[] u : uninherited) {
            if (u[0] <= t) {
                best = u[1];
            } else {
                break;
            }
        }

        return best;
    }

    private double velocityAt(double t) {
        double best = 1;

        for (double[] i : inherited) {
            if (i[0] <= t) {
                best = i[1];
            } else {
                break;
            }
        }

        return best;
    }

    private void hitObject(String line) {
        String[] p = line.split(",");
        Obj o = new Obj();
        o.x = Double.parseDouble(p[0]);
        o.y = Double.parseDouble(p[1]);
        o.time = Double.parseDouble(p[2]);
        int type = Integer.parseInt(p[3]);
        o.newCombo = (type & 4) != 0;
        o.comboIndex = (type >> 4) & 7;

        o.hitSound = p.length > 4 ? Integer.parseInt(p[4].strip()) : 0;

        if ((type & 1) != 0) {
            o.type = CIRCLE;
            o.endTime = o.time;
            hitSample(o, p.length > 5 ? p[5] : "");
        } else if ((type & 2) != 0) {
            o.type = SLIDER;
            readSlider(o, p);
            hitSample(o, p.length > 10 ? p[10] : "");
        } else if ((type & 8) != 0) {
            o.type = SPINNER;
            o.endTime = Double.parseDouble(p[5]);
            hitSample(o, p.length > 6 ? p[6] : "");
        } else {
            return;
        }

        objects.add(o);
    }

    // "normalSet:additionSet:index:volume:filename", every part optional
    private static void hitSample(Obj o, String text) {
        String[] f = text.strip().split(":");

        try {
            o.normalSet = f.length > 0 && !f[0].isEmpty() ? Integer.parseInt(f[0]) : 0;
            o.additionSet = f.length > 1 ? Integer.parseInt(f[1]) : 0;
            o.sampleIndex = f.length > 2 ? Integer.parseInt(f[2]) : 0;
            o.sampleVolume = f.length > 3 ? Integer.parseInt(f[3]) : 0;
        } catch (NumberFormatException ignored) {
            // leave the inherited defaults
        }
    }

    /** The sample set, index and volume the timing points give at {@code t}: {set, index, volume}. */
    public int[] samplesAt(double t) {
        double[] best = samplePoints.isEmpty() ? new double[] { 0, defaultSampleSet, 1, 100 } : samplePoints.get(0);

        for (double[] sp : samplePoints) {
            if (sp[0] <= t) {
                best = sp;
            } else {
                break;
            }
        }

        int set = (int) best[1] == 0 ? defaultSampleSet : (int) best[1];
        return new int[] { set, Math.max(1, (int) best[2]), (int) best[3] };
    }

    private void readSlider(Obj o, String[] p) {
        String[] curve = p[5].split("\\|");
        char kind = curve[0].charAt(0);
        o.slides = Math.max(1, Integer.parseInt(p[6]));
        o.pixelLength = Double.parseDouble(p[7]);

        List<double[]> pts = new ArrayList<>();
        pts.add(new double[] { o.x, o.y });

        for (int i = 1; i < curve.length; i++) {
            String[] xy = curve[i].split(":");
            pts.add(new double[] { Double.parseDouble(xy[0]), Double.parseDouble(xy[1]) });
        }

        List<double[]> poly = SliderCurve.flatten(kind, pts);
        poly = SliderCurve.fitLength(poly, o.pixelLength);

        o.px = new double[poly.size()];
        o.py = new double[poly.size()];
        o.dist = new double[poly.size()];

        for (int i = 0; i < poly.size(); i++) {
            o.px[i] = poly.get(i)[0];
            o.py[i] = poly.get(i)[1];

            if (i > 0) {
                o.dist[i] = o.dist[i - 1] + Math.hypot(o.px[i] - o.px[i - 1], o.py[i] - o.py[i - 1]);
            }
        }

        // one sound per edge: the head, every repeat and the tail
        int edges = o.slides + 1;
        o.edgeSounds = new int[edges];
        o.edgeSets = new int[edges][2];
        String[] sounds = p.length > 8 ? p[8].split("\\|") : new String[0];
        String[] sets = p.length > 9 ? p[9].split("\\|") : new String[0];

        for (int i = 0; i < edges; i++) {
            o.edgeSounds[i] = i < sounds.length && !sounds[i].isBlank() ? Integer.parseInt(sounds[i].strip()) : o.hitSound;

            if (i < sets.length && sets[i].contains(":")) {
                String[] ns = sets[i].split(":");
                o.edgeSets[i][0] = Integer.parseInt(ns[0].strip());
                o.edgeSets[i][1] = Integer.parseInt(ns[1].strip());
            }
        }

        double beat = beatLengthAt(o.time);
        double velocity = sliderMultiplier * 100 * velocityAt(o.time);
        o.spanDuration = o.pixelLength / velocity * beat;
        o.endTime = o.time + o.spanDuration * o.slides;

        // Ticks sit every (beat / tickRate) of travel; none within a hair of the ends.
        double tickSpan = beat / sliderTickRate;

        if (tickSpan > 0) {
            for (double t = tickSpan; t < o.spanDuration - 10; t += tickSpan) {
                o.tickFractions.add(t / o.spanDuration);
            }
        }
    }

    private void finish() {
        if (comboColors.isEmpty()) {
            comboColors.add(new Color(255, 192, 0));
            comboColors.add(new Color(0, 202, 0));
            comboColors.add(new Color(18, 124, 255));
            comboColors.add(new Color(242, 24, 57));
        }

        int combo = 0;
        int colour = 0;

        for (int i = 0; i < objects.size(); i++) {
            Obj o = objects.get(i);

            if (o.newCombo || i == 0 || o.type == SPINNER) {
                combo = 0;

                if (i > 0) {
                    colour += 1 + o.comboIndex;
                }
            }

            combo++;
            o.comboNumber = combo;
            o.comboIndex = colour % comboColors.size();
        }
    }
}
