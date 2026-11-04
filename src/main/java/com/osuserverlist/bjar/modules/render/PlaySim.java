package com.osuserverlist.bjar.modules.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a replay against a beatmap and works out what the game would have shown: the judgement of every object and
 * a timeline of combo, accuracy, score share and health for the HUD.
 *
 * <p>This is a model of the game's rules, not a copy of them, so the exact counts can differ by a few objects from
 * the stored score. {@link #calibrate} pulls the totals towards the stored numbers, and the results screen shows
 * the stored numbers themselves.
 */
public final class PlaySim {

    public static final int MOD_EZ = 2, MOD_HD = 8, MOD_HR = 16, MOD_DT = 64, MOD_HT = 256, MOD_NC = 512, MOD_FL = 1024;

    /** Outcome of one hit object. {@code result}: 300, 100, 50 or 0. */
    public static final class Result {
        public int result;
        public double time;
        public double x, y;
        public boolean headHit;
        public double spun;
        public double spinRequired;
        /** When the head of a circle or slider was hit (or, if it was missed, when it was given up on). */
        public double headTime;
        /** Click time minus object time for a hit circle or slider head, in beatmap ms; NaN when there is none. */
        public double error = Double.NaN;
        public boolean[] tickHit = new boolean[0];
    }

    /** One step of the HUD state, effective from {@code time} on. */
    public static final class Tick {
        public final double time;
        public final int combo;
        public final double accuracy;
        public final double scoreShare;
        public final double health;

        Tick(double time, int combo, double accuracy, double scoreShare, double health) {
            this.time = time;
            this.combo = combo;
            this.accuracy = accuracy;
            this.scoreShare = scoreShare;
            this.health = health;
        }
    }

    public final OsuMap map;
    public final OsrReplay replay;
    public final double rate;
    public final double cs, ar, od, radius;
    public final double preempt, fadeIn;
    public final boolean flipY;
    public final Result[] results;
    public final List<Tick> timeline = new ArrayList<>();
    public int simMisses;

    private final double w300, w100, w50;

    public PlaySim(OsuMap map, OsrReplay replay) {
        this.map = map;
        this.replay = replay;

        int mods = replay.mods;
        boolean hr = (mods & MOD_HR) != 0;
        boolean ez = (mods & MOD_EZ) != 0;
        this.flipY = hr;
        this.rate = (mods & (MOD_DT | MOD_NC)) != 0 ? 1.5 : (mods & MOD_HT) != 0 ? 0.75 : 1.0;

        double csv = map.cs;
        double arv = map.ar;
        double odv = map.od;

        if (hr) {
            csv = Math.min(10, csv * 1.3);
            arv = Math.min(10, arv * 1.4);
            odv = Math.min(10, odv * 1.4);
        } else if (ez) {
            csv *= 0.5;
            arv *= 0.5;
            odv *= 0.5;
        }

        this.cs = csv;
        this.ar = arv;
        this.od = odv;
        this.radius = 54.4 - 4.48 * csv;

        // Approach rate is a time before the hit, so it is already in beatmap time and needs no rate.
        this.preempt = arv < 5 ? 1200 + 600 * (5 - arv) / 5 : arv == 5 ? 1200 : 1200 - 750 * (arv - 5) / 5;
        this.fadeIn = 400 * Math.min(1, preempt / 450);

        this.w300 = (80 - 6 * odv) * rate;
        this.w100 = (140 - 8 * odv) * rate;
        this.w50 = (200 - 10 * odv) * rate;

        if (hr) {
            for (OsuMap.Obj o : map.objects) {
                o.y = 384 - o.y;

                for (int i = 0; i < o.py.length; i++) {
                    o.py[i] = 384 - o.py[i];
                }
            }
        }

        this.results = new Result[map.objects.size()];
        judge();
        alignClock();
        calibrate();
        buildTimeline();
    }

    // ------------------------------------------------------------------ cursor

    public float[] cursorAt(double t) {
        List<OsrReplay.Frame> f = replay.frames;

        if (f.isEmpty()) {
            return new float[] { 256, 192 };
        }

        int lo = 0;
        int hi = f.size() - 1;

        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;

            if (f.get(mid).time() <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        OsrReplay.Frame a = f.get(lo);

        if (lo + 1 >= f.size() || t <= a.time()) {
            return new float[] { a.x(), a.y() };
        }

        OsrReplay.Frame b = f.get(lo + 1);
        double span = b.time() - a.time();
        double k = span <= 0 ? 0 : Math.min(1, (t - a.time()) / span);
        return new float[] { (float) (a.x() + (b.x() - a.x()) * k), (float) (a.y() + (b.y() - a.y()) * k) };
    }

    private boolean keyDownAt(double t) {
        List<OsrReplay.Frame> f = replay.frames;
        int lo = 0;
        int hi = f.size() - 1;

        if (f.isEmpty() || f.get(0).time() > t) {
            return false;
        }

        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;

            if (f.get(mid).time() <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return f.get(lo).keys() != 0;
    }

    private record Click(double time, float x, float y) {
    }

    private List<Click> clicks() {
        List<Click> out = new ArrayList<>();
        boolean k1 = false;
        boolean k2 = false;

        for (OsrReplay.Frame fr : replay.frames) {
            boolean n1 = (fr.keys() & OsrReplay.KEY_1) != 0;
            boolean n2 = (fr.keys() & OsrReplay.KEY_2) != 0;

            if ((n1 && !k1) || (n2 && !k2)) {
                out.add(new Click(fr.time(), fr.x(), fr.y()));
            }

            k1 = n1;
            k2 = n2;
        }

        return out;
    }

    // ------------------------------------------------------------------ judging

    private void judge() {
        List<OsuMap.Obj> objs = map.objects;
        List<Click> clicks = clicks();
        int ci = 0;

        for (int i = 0; i < objs.size(); i++) {
            OsuMap.Obj o = objs.get(i);
            Result r = new Result();
            r.x = o.x;
            r.y = o.y;
            r.time = o.time;
            r.headTime = o.time + w50;
            results[i] = r;

            if (o.type == OsuMap.SPINNER) {
                judgeSpinner(o, r);
                continue;
            }

            // first click that could belong to this object
            boolean hit = false;

            while (ci < clicks.size()) {
                Click c = clicks.get(ci);

                if (c.time() < o.time - w50 - 100 * rate) {
                    ci++; // too early for this object, and not close to anything: a stray tap
                    continue;
                }

                if (c.time() > o.time + w50) {
                    break; // this object is already lost
                }

                double dist = Math.hypot(c.x() - o.x, c.y() - o.y);

                if (dist <= radius * 1.0 && c.time() >= o.time - w50) {
                    double err = Math.abs(c.time() - o.time);
                    r.result = err <= w300 ? 300 : err <= w100 ? 100 : 50;
                    r.time = c.time();
                    r.headTime = c.time();
                    r.error = c.time() - o.time;
                    r.headHit = true;
                    ci++;
                    hit = true;
                    break;
                }

                ci++; // a click that missed the circle is spent
            }

            if (!hit) {
                r.result = 0;
                r.time = o.time + w50;
            }

            if (o.type == OsuMap.SLIDER) {
                judgeSlider(o, r);
            }
        }
    }

    // Following the ball: key held and the cursor inside the follow circle at each tick, repeat and the end.
    private void judgeSlider(OsuMap.Obj o, Result r) {
        double follow = radius * 2.4;
        int ticksPerSpan = o.tickFractions.size();
        List<Boolean> hits = new ArrayList<>();
        hits.add(r.headHit);

        for (int s = 0; s < o.slides; s++) {
            for (double frac : o.tickFractions) {
                double t = o.time + o.spanDuration * (s + ((s & 1) == 1 ? 1 - frac : frac));
                hits.add(following(o, t, s, (s & 1) == 1 ? 1 - frac : frac, follow));
            }

            double tEnd = o.time + o.spanDuration * (s + 1);
            boolean last = s == o.slides - 1;
            // the final element is judged slightly before the end, like the game does
            double tCheck = last ? Math.max(o.time + o.spanDuration * s, tEnd - 36 * rate) : tEnd;
            hits.add(following(o, tCheck, s, ((s & 1) == 1) ? 0 : 1, follow));
        }

        r.tickHit = new boolean[hits.size()];
        int got = 0;

        for (int i = 0; i < hits.size(); i++) {
            r.tickHit[i] = hits.get(i);

            if (hits.get(i)) {
                got++;
            }
        }

        double share = (double) got / hits.size();
        r.result = share >= 1 ? 300 : share >= 0.5 ? 100 : share > 0 ? 50 : 0;
        r.time = o.endTime;

        if (ticksPerSpan < 0) {
            r.result = 0;
        }
    }

    private boolean following(OsuMap.Obj o, double t, int span, double frac, double follow) {
        if (!keyDownAt(t)) {
            return false;
        }

        float[] c = cursorAt(t);
        double[] p = o.pointAtFraction(frac);
        return Math.hypot(c[0] - p[0], c[1] - p[1]) <= follow;
    }

    private void judgeSpinner(OsuMap.Obj o, Result r) {
        double step = 16;
        double total = 0;
        double lastAngle = Double.NaN;

        for (double t = o.time; t <= o.endTime; t += step) {
            float[] c = cursorAt(t);
            double a = Math.atan2(c[1] - 192, c[0] - 256);

            if (!Double.isNaN(lastAngle)) {
                double d = a - lastAngle;

                while (d > Math.PI) {
                    d -= 2 * Math.PI;
                }

                while (d < -Math.PI) {
                    d += 2 * Math.PI;
                }

                total += Math.abs(d);
            }

            lastAngle = a;
        }

        double rotations = total / (2 * Math.PI);
        double seconds = (o.endTime - o.time) / 1000 / rate;
        double required = Math.max(1, seconds * (od < 5 ? 2.0 + 0.4 * od : 3.0 + 0.2 * od) * 0.7);
        double ratio = rotations / required;

        r.spun = rotations;
        r.spinRequired = required;
        r.result = ratio >= 1 ? 300 : ratio >= 0.9 ? 100 : ratio >= 0.75 ? 50 : 0;
        r.time = o.endTime;
        r.x = 256;
        r.y = 192;
    }

    // The stored frame times can sit a constant distance off the beatmap clock (the client's audio latency is not
    // part of the file). A real player's clicks average out near zero, so when the median error of the circle hits
    // is clearly not, the whole replay is shifted by it and judged again.
    private void alignClock() {
        List<Double> errors = new ArrayList<>();

        for (int i = 0; i < results.length; i++) {
            OsuMap.Obj o = map.objects.get(i);

            if (o.type == OsuMap.CIRCLE && results[i].result > 0) {
                errors.add(results[i].time - o.time);
            }
        }

        if (errors.size() < 10) {
            return;
        }

        errors.sort(Double::compare);
        double median = errors.get(errors.size() / 2);

        if (Math.abs(median) < 6 || Math.abs(median) > w50) {
            return;
        }

        List<OsrReplay.Frame> shifted = new ArrayList<>(replay.frames.size());

        for (OsrReplay.Frame f : replay.frames) {
            shifted.add(new OsrReplay.Frame((int) Math.round(f.time() - median), f.x(), f.y(), f.keys(), f.raw()));
        }

        replay.frames = shifted;
        clockShift = -median;
        judge();
    }

    /** How far the replay was moved to line it up with the beatmap, in ms (0 when it already was). */
    public double clockShift;

    // ------------------------------------------------------------------ calibration

    // Misses the model invents (it does not know every nuance of the game's hit rules) are turned into 50s until
    // the total matches the stored score, so the HUD never shows a combo break the real play did not have.
    private void calibrate() {
        int sim = 0;

        for (Result r : results) {
            if (r.result == 0) {
                sim++;
            }
        }

        simMisses = sim;
        int extra = sim - replay.nMiss;

        for (int i = 0; i < results.length && extra > 0; i++) {
            Result r = results[i];

            if (r.result == 0) {
                r.result = 50;
                r.headHit = true;
                r.headTime = map.objects.get(i).time;

                for (int k = 0; k < r.tickHit.length; k++) {
                    r.tickHit[k] = true;
                }

                extra--;
            }
        }
    }

    // ------------------------------------------------------------------ timeline

    private static int weight(int result) {
        return result;
    }

    private void buildTimeline() {
        record Ev(double time, int kind, int value) {
        } // kind 0 judgement (value = result), 1 element hit, 2 element break

        List<Ev> events = new ArrayList<>();
        double totalWeight = 0;

        for (int i = 0; i < results.length; i++) {
            OsuMap.Obj o = map.objects.get(i);
            Result r = results[i];

            if (o.type == OsuMap.SLIDER) {
                int idx = 0;
                events.add(new Ev(r.headHit ? r.headTime : o.time + w50, r.tickHit.length > 0 && r.tickHit[idx] ? 1 : 2, 30));
                idx++;

                for (int s = 0; s < o.slides; s++) {
                    for (double frac : o.tickFractions) {
                        double t = o.time + o.spanDuration * (s + ((s & 1) == 1 ? 1 - frac : frac));
                        events.add(new Ev(t, idx < r.tickHit.length && r.tickHit[idx] ? 1 : 2, 10));
                        idx++;
                    }

                    double t = o.time + o.spanDuration * (s + 1);
                    events.add(new Ev(t, idx < r.tickHit.length && r.tickHit[idx] ? 1 : 2, 30));
                    idx++;
                }

                totalWeight += 30 + 10 * o.tickFractions.size() * o.slides + 30 * o.slides + weight(300);
                events.add(new Ev(o.endTime + 1, 0, r.result));
            } else {
                totalWeight += weight(300);
                events.add(new Ev(r.time, 0, r.result));

                if (r.result == 0) {
                    events.add(new Ev(r.time, 2, 0));
                } else {
                    events.add(new Ev(r.time, 1, 0));
                }
            }
        }

        events.sort((a, b) -> Double.compare(a.time(), b.time()));

        int combo = 0;
        int judged = 0;
        double accPoints = 0;
        double gained = 0;
        double health = 1;
        double lastTime = events.isEmpty() ? 0 : events.get(0).time();
        double drain = 0.004 + 0.0015 * map.hp; // per second

        timeline.add(new Tick(-1e9, 0, 1, 0, 1));

        for (Ev e : events) {
            health -= drain * Math.max(0, (e.time() - lastTime) / 1000 / rate);
            lastTime = e.time();

            switch (e.kind()) {
                case 0 -> {
                    judged++;
                    int v = e.value();
                    accPoints += v;
                    gained += weight(v);

                    health += v == 300 ? 0.035 : v == 100 ? 0.02 : v == 50 ? 0.008 : -0.12;
                }
                case 1 -> {
                    combo++;

                    // The model cannot tell where the real combo broke, only how high it got: never show more.
                    if (replay.maxCombo > 0 && combo > replay.maxCombo) {
                        combo = 1;
                    }

                    if (e.value() > 0) {
                        gained += e.value();
                    }
                }
                default -> combo = 0;
            }

            health = Math.max(0.04, Math.min(1, health));
            double acc = judged == 0 ? 1 : accPoints / (300.0 * judged);
            timeline.add(new Tick(e.time(), combo, acc, totalWeight <= 0 ? 0 : Math.min(1, gained / totalWeight), health));
        }
    }

    public Tick tickAt(double t) {
        int lo = 0;
        int hi = timeline.size() - 1;

        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;

            if (timeline.get(mid).time <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return timeline.get(lo);
    }

    // Stable's combo for a judged slider counts the head as soon as it is hit; spinner / circle results are
    // pushed at their judgement time. The rate-adjusted windows are exposed for the renderer's hit-error flash.
    public double hitWindow50() {
        return w50;
    }

    public double hitWindow100() {
        return w100;
    }

    public double hitWindow300() {
        return w300;
    }
}
