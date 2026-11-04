package com.osuserverlist.bjar.modules.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;

/**
 * The hit sounds of a play as an audio track of its own: a stereo 44.1 kHz WAV, as long as the video, with a sample
 * placed at every moment the game would have played one.
 *
 * <p>What is played, and when, follows osu!lazer (osu-master):
 * <ul>
 *   <li>A hit object plays its samples when it is hit, at the time of the hit. The normal sample always plays, and
 *       whistle, finish and clap are added on top when the object asks for them.</li>
 *   <li>The bank (normal / soft / drum), index and volume are the object's own; a value of 0 means "take it from the
 *       timing point" that is active at the sample's own time, so every edge of a slider is looked up at its own
 *       time.</li>
 *   <li>Additions use the bank given for them, else the object's normal bank.</li>
 *   <li>A slider tick plays {@code slidertick} in the bank of the slider's normal sample.</li>
 *   <li>While a slider is followed {@code sliderslide} loops, and {@code sliderwhistle} too when the slider carries a
 *       whistle. A spinner loops {@code spinnerspin}, rings {@code spinnerbonus} for every turn beyond what was
 *       needed, and plays its samples once it is cleared.</li>
 * </ul>
 *
 * <p>The sound files come from the beatmap's osz when it ships them, then from the server skin, then from the default
 * skin bundled in the jar. A file that is present but empty means the skin wants silence there.
 */
final class HitsoundTrack {

    static final int RATE = 44100;
    private static final double GAIN = 0.7;
    /** The game looks the timing point up this far after the sound's own time. */
    private static final double CONTROL_POINT_LENIENCY = 5;

    private record Event(double sec, String sample, int index, double volume) {
    }

    private record Loop(double fromSec, double toSec, String sample, int index, double volume) {
    }

    private final PlaySim sim;
    private final OsuMap map;
    private final SkinSet skin;
    private final Map<String, float[]> decoded = new HashMap<>();
    private final String ffmpeg;

    private HitsoundTrack(PlaySim sim, SkinSet skin) {
        this.sim = sim;
        this.map = sim.map;
        this.skin = skin;
        this.ffmpeg = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);
    }

    /**
     * @param startMs      beatmap time at the first frame
     * @param playSeconds  length of the gameplay part, in video seconds
     * @param totalSeconds length of the whole video; the track is silent after the play
     * @param target       WAV file to write
     */
    static void build(PlaySim sim, SkinSet skin, double startMs, double playSeconds, double totalSeconds, Path target)
            throws IOException, InterruptedException {
        new HitsoundTrack(sim, skin).write(startMs, playSeconds, totalSeconds, target);
    }

    // ------------------------------------------------------------------ events

    private List<Event> events(double startMs, double playSeconds) {
        List<Event> out = new ArrayList<>();

        for (int i = 0; i < map.objects.size(); i++) {
            OsuMap.Obj o = map.objects.get(i);
            PlaySim.Result r = sim.results[i];

            if (o.type == OsuMap.CIRCLE) {
                if (r.result > 0) {
                    addHit(out, o, o.hitSound, o.normalSet, o.additionSet, r.headTime, o.time, startMs, playSeconds);
                }
            } else if (o.type == OsuMap.SLIDER) {
                sliderEvents(out, o, r, startMs, playSeconds);
            } else if (r.result > 0) {
                // a cleared spinner plays its samples when it ends
                addHit(out, o, o.hitSound, o.normalSet, o.additionSet, o.endTime, o.endTime, startMs, playSeconds);
                spinnerBonus(out, o, r, startMs, playSeconds);
            }
        }

        return out;
    }

    private void sliderEvents(List<Event> out, OsuMap.Obj o, PlaySim.Result r, double startMs, double playSeconds) {
        int ticks = o.tickFractions.size();

        // head, repeats and tail: each is a node with its own sound bits and banks, looked up at its own time
        for (int e = 0; e <= o.slides; e++) {
            int idx = e == 0 ? 0 : 1 + (e - 1) * (ticks + 1) + ticks;
            boolean hit = e == 0 ? r.headHit : idx < r.tickHit.length && r.tickHit[idx];

            if (!hit) {
                continue;
            }

            double nodeTime = o.time + o.spanDuration * e;
            double playTime = e == 0 ? r.headTime : nodeTime;
            addHit(out, o, o.edgeSounds[e], o.edgeSets[e][0], o.edgeSets[e][1], playTime, nodeTime, startMs, playSeconds);
        }

        // ticks use the bank of the slider's normal sample, with the volume of the timing point at the tick
        for (int s = 0; s < o.slides; s++) {
            for (int j = 0; j < ticks; j++) {
                int idx = 1 + s * (ticks + 1) + j;

                if (idx >= r.tickHit.length || !r.tickHit[idx]) {
                    continue;
                }

                double frac = o.tickFractions.get(j);
                double t = o.time + o.spanDuration * (s + ((s & 1) == 1 ? 1 - frac : frac));
                int[] cp = map.samplesAt(t + CONTROL_POINT_LENIENCY);
                int bank = o.normalSet != 0 ? o.normalSet : cp[0];
                add(out, t, setName(bank) + "-slidertick", o.sampleIndex > 0 ? o.sampleIndex : cp[1],
                        o.sampleVolume > 0 ? o.sampleVolume : cp[2], startMs, playSeconds);
            }
        }
    }

    // one extra "ding" for every full turn beyond the ones the spinner needed, spread over the time it was kept spinning
    private void spinnerBonus(List<Event> out, OsuMap.Obj o, PlaySim.Result r, double startMs, double playSeconds) {
        int bonus = (int) Math.min(40, Math.floor(r.spun - r.spinRequired));

        if (bonus <= 0 || r.spun <= 0) {
            return;
        }

        double clearedAt = o.time + (o.endTime - o.time) * Math.min(1, r.spinRequired / r.spun);
        int[] cp = map.samplesAt(o.endTime + CONTROL_POINT_LENIENCY);

        for (int b = 1; b <= bonus; b++) {
            double t = clearedAt + (o.endTime - clearedAt) * b / (bonus + 1.0);
            add(out, t, "spinnerbonus", 1, o.sampleVolume > 0 ? o.sampleVolume : cp[2], startMs, playSeconds);
        }
    }

    // a hit: the normal sample always, plus whistle, finish and clap when the sound bits ask for them
    private void addHit(List<Event> out, OsuMap.Obj o, int sounds, int normalSet, int additionSet, double playTime,
            double lookupTime, double startMs, double playSeconds) {
        int[] cp = map.samplesAt(lookupTime + CONTROL_POINT_LENIENCY);
        int normalBank = normalSet != 0 ? normalSet : cp[0];
        // additions follow their own bank when it is set, otherwise the bank of the normal sample
        int additionBank = additionSet != 0 ? additionSet : normalBank;
        int index = o.sampleIndex > 0 ? o.sampleIndex : cp[1];
        int volume = o.sampleVolume > 0 ? o.sampleVolume : cp[2];

        add(out, playTime, setName(normalBank) + "-hitnormal", index, volume, startMs, playSeconds);

        if ((sounds & 2) != 0) {
            add(out, playTime, setName(additionBank) + "-hitwhistle", index, volume, startMs, playSeconds);
        }

        if ((sounds & 4) != 0) {
            add(out, playTime, setName(additionBank) + "-hitfinish", index, volume, startMs, playSeconds);
        }

        if ((sounds & 8) != 0) {
            add(out, playTime, setName(additionBank) + "-hitclap", index, volume, startMs, playSeconds);
        }
    }

    private void add(List<Event> out, double mapTime, String sample, int index, int volume, double startMs,
            double playSeconds) {
        double sec = (mapTime - startMs) / 1000.0 / sim.rate;

        if (sec < 0 || sec >= playSeconds) {
            return;
        }

        out.add(new Event(sec, sample, index, Math.max(0, Math.min(100, volume)) / 100.0));
    }

    // ------------------------------------------------------------------ loops

    private List<Loop> loops(double startMs, double playSeconds) {
        List<Loop> out = new ArrayList<>();

        for (int i = 0; i < map.objects.size(); i++) {
            OsuMap.Obj o = map.objects.get(i);
            PlaySim.Result r = sim.results[i];

            if (o.type == OsuMap.SLIDER && r.result > 0) {
                sliderLoops(out, o, r, startMs, playSeconds);
            } else if (o.type == OsuMap.SPINNER && r.spun > 0) {
                int[] cp = map.samplesAt(o.time + CONTROL_POINT_LENIENCY);
                addLoop(out, o.time, o.endTime, "spinnerspin", 1, (o.sampleVolume > 0 ? o.sampleVolume : cp[2]) * 0.5,
                        startMs, playSeconds);
            }
        }

        return out;
    }

    // The slide sound runs while the ball is followed: from the first element that was hit to the first one that was lost.
    private void sliderLoops(List<Loop> out, OsuMap.Obj o, PlaySim.Result r, double startMs, double playSeconds) {
        int ticks = o.tickFractions.size();
        List<double[]> elements = new ArrayList<>(); // time of each judged element, in the order of tickHit
        elements.add(new double[] { o.time });

        for (int s = 0; s < o.slides; s++) {
            for (double frac : o.tickFractions) {
                elements.add(new double[] { o.time + o.spanDuration * (s + ((s & 1) == 1 ? 1 - frac : frac)) });
            }

            elements.add(new double[] { o.time + o.spanDuration * (s + 1) });
        }

        double from = -1;
        double to = o.endTime;

        for (int k = 0; k < elements.size() && k < r.tickHit.length; k++) {
            boolean hit = k == 0 ? r.headHit || r.tickHit[0] : r.tickHit[k];

            if (hit && from < 0) {
                from = elements.get(k)[0];
            } else if (!hit && from >= 0) {
                to = elements.get(k)[0];
                break;
            }
        }

        if (from < 0 || to <= from) {
            return;
        }

        int[] cp = map.samplesAt(o.time + CONTROL_POINT_LENIENCY);
        int normalBank = o.normalSet != 0 ? o.normalSet : cp[0];
        int additionBank = o.additionSet != 0 ? o.additionSet : normalBank;
        int index = o.sampleIndex > 0 ? o.sampleIndex : cp[1];
        int volume = o.sampleVolume > 0 ? o.sampleVolume : cp[2];

        addLoop(out, from, to, setName(normalBank) + "-sliderslide", index, volume, startMs, playSeconds);

        if ((o.hitSound & 2) != 0) {
            addLoop(out, from, to, setName(additionBank) + "-sliderwhistle", index, volume, startMs, playSeconds);
        }

        if (ticks < 0) {
            return;
        }
    }

    private void addLoop(List<Loop> out, double fromMs, double toMs, String sample, int index, double volume,
            double startMs, double playSeconds) {
        double from = Math.max(0, (fromMs - startMs) / 1000.0 / sim.rate);
        double to = Math.min(playSeconds, (toMs - startMs) / 1000.0 / sim.rate);

        if (to <= from) {
            return;
        }

        out.add(new Loop(from, to, sample, index, Math.max(0, Math.min(100, volume)) / 100.0));
    }

    private static String setName(int set) {
        return set == 2 ? "soft" : set == 3 ? "drum" : "normal";
    }

    // ------------------------------------------------------------------ samples

    private float[] sample(String name, int index) throws IOException, InterruptedException {
        String key = name + "#" + index;
        float[] cached = decoded.get(key);

        if (cached != null || decoded.containsKey(key)) {
            return cached;
        }

        byte[] data = null;
        String ext = ".wav";

        // the beatmap's own file first: name2.wav for index 2, plain name.wav for 1, in any of three formats
        for (String e : new String[] { ".wav", ".ogg", ".mp3" }) {
            if (index > 1) {
                data = skin.file(name + index + e);
            }

            if (data == null) {
                data = skin.file(name + e);
            }

            if (data != null) {
                ext = e;
                break;
            }
        }

        // then the server skin's own sounds, then the default skin's
        for (String path : new String[] { "/render/skin-misuzu/" + name + ".ogg", "/render/skin-misuzu/" + name + ".wav",
                "/render/samples/" + name + ".wav" }) {
            if (data != null) {
                break;
            }

            try (InputStream in = HitsoundTrack.class.getResourceAsStream(path)) {
                if (in != null) {
                    data = in.readAllBytes();
                    ext = path.substring(path.lastIndexOf('.'));
                }
            }
        }

        // an empty or near-empty file (a bare WAV header) is a skin muting the sound
        float[] pcm = data == null || data.length < 100 ? null : decode(data, ext);
        decoded.put(key, pcm);
        return pcm;
    }

    // ffmpeg turns whatever the sample is into 44.1 kHz stereo 16 bit; it copes with every codec an osz may carry
    private float[] decode(byte[] data, String ext) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("bjar-sample-", ext);

        try {
            Files.write(tmp, data);
            Process p = new ProcessBuilder(ffmpeg, "-v", "error", "-i", tmp.toString(), "-f", "s16le", "-ac", "2", "-ar",
                    String.valueOf(RATE), "-").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] raw = p.getInputStream().readAllBytes();
            p.waitFor(30, TimeUnit.SECONDS);

            if (raw.length < 4) {
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] pcm = new float[raw.length / 2];

            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = buf.getShort() / 32768f;
            }

            return pcm;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ mixing

    private void write(double startMs, double playSeconds, double totalSeconds, Path target)
            throws IOException, InterruptedException {
        int frames = (int) Math.ceil(totalSeconds * RATE);
        float[] mix = new float[frames * 2];

        for (Loop l : loops(startMs, playSeconds)) {
            float[] pcm = sample(l.sample(), l.index());

            if (pcm == null || pcm.length < 2) {
                continue;
            }

            int from = (int) (l.fromSec() * RATE) * 2;
            int to = Math.min(mix.length, (int) (l.toSec() * RATE) * 2);
            float gain = (float) (l.volume() * GAIN);
            int fade = (int) (0.03 * RATE) * 2;

            // the loop sample is tiled over the time the slider is followed or the spinner is spun
            for (int at = from, k = 0; at < to; at++, k++) {
                float env = to - at < fade ? (float) (to - at) / fade : 1f;
                mix[at] += pcm[k % pcm.length] * gain * env;
            }
        }

        for (Event e : events(startMs, playSeconds)) {
            float[] pcm = sample(e.sample(), e.index());

            if (pcm == null) {
                continue;
            }

            int at = (int) (e.sec() * RATE) * 2;
            float gain = (float) (e.volume() * GAIN);

            for (int i = 0; i < pcm.length && at + i < mix.length; i++) {
                mix[at + i] += pcm[i] * gain;
            }
        }

        ByteBuffer pcm = ByteBuffer.allocate(mix.length * 2).order(ByteOrder.LITTLE_ENDIAN);

        for (float v : mix) {
            // many simultaneous hits can pass full scale; a soft knee keeps that from crackling
            double x = Math.max(-1.5, Math.min(1.5, v));
            double y = Math.abs(x) <= 0.8 ? x : Math.signum(x) * (0.8 + 0.2 * Math.tanh((Math.abs(x) - 0.8) / 0.2));
            pcm.putShort((short) Math.round(y * 32767));
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream(44);
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.capacity()).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4).putShort((short) 4)
                .putShort((short) 16).put("data".getBytes()).putInt(pcm.capacity());
        header.write(h.array());

        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(header.toByteArray());
            out.write(pcm.array());
        }
    }
}
