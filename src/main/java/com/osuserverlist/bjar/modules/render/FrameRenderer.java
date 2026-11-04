package com.osuserverlist.bjar.modules.render;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Draws single frames of a replay: the playfield at a given beatmap time, and the results screen. */
public final class FrameRenderer {

    /** The layout is worked out on a 1280 x 720 page; the picture is drawn at 1920 x 1080 by scaling that page up. */
    public static final int W = 1280;
    public static final int H = 720;
    public static final int OUT_W = 1920;
    public static final int OUT_H = 1080;
    private static final double SCALE = OUT_W / (double) W;

    /** Facts shown on the results screen. Everything here comes from the stored score, not from the simulation. */
    public record ScoreInfo(String player, long score, int maxCombo, int mapMaxCombo, double accuracy, int n300, int n100,
            int n50, int nMiss, int nGeki, int nKatu, String grade, double pp, String mods, String playedAt) {
    }

    private static final double PF = 1.5; // 480 -> 720 lines
    private static final double OX = (W - 512 * PF) / 2;
    private static final double OY = (H - 384 * PF) / 2 + 8;

    private final PlaySim sim;
    private final OsuMap map;
    private final SkinSet skin;
    private final ScoreInfo info;
    private final BufferedImage background; // already dimmed and cropped to W x H
    private final BufferedImage brightBackground; // the lighter one shown during breaks
    private final double k; // circle sprite factor: logical 128px sprite -> on-screen pixels
    private final Map<Integer, Path2D> bodyCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Beatmap time of the first frame; the mod icons are shown for the first seconds after it. */
    private double clipStart;
    /** Times (beatmap ms) every key went down: K1, K2, M1, M2. */
    private final double[][] pressTimes = new double[4][];

    private static final Font F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 26);
    private static final Font F_REG = new Font(Font.SANS_SERIF, Font.PLAIN, 20);

    public FrameRenderer(PlaySim sim, SkinSet skin, BufferedImage rawBackground, ScoreInfo info) {
        this.sim = sim;
        this.map = sim.map;
        this.skin = skin;
        this.info = info;
        this.k = 2 * sim.radius * PF / 128.0;
        this.background = prepareBackground(rawBackground, 175);
        this.brightBackground = prepareBackground(rawBackground, 70);
        buildKeyPresses();
    }

    public void setClipStart(double ms) {
        this.clipStart = ms;
    }

    private static BufferedImage prepareBackground(BufferedImage raw, int dim) {
        BufferedImage out = new BufferedImage(OUT_W, OUT_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        hints(g);

        if (raw == null) {
            g.setPaint(new GradientPaint(0, 0, new Color(38, 30, 60), OUT_W, OUT_H, new Color(14, 12, 24)));
            g.fillRect(0, 0, OUT_W, OUT_H);
        } else {
            // cover: scale to fill, centre-crop
            double s = Math.max((double) OUT_W / raw.getWidth(), (double) OUT_H / raw.getHeight());
            int dw = (int) Math.ceil(raw.getWidth() * s);
            int dh = (int) Math.ceil(raw.getHeight() * s);
            g.drawImage(raw, (OUT_W - dw) / 2, (OUT_H - dh) / 2, dw, dh, null);
            g.setColor(new Color(0, 0, 0, dim)); // background dim
            g.fillRect(0, 0, OUT_W, OUT_H);
        }

        g.dispose();
        return out;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static double sx(double x) {
        return OX + x * PF;
    }

    private static double sy(double y) {
        return OY + y * PF;
    }

    // ------------------------------------------------------------------ primitives

    private static void alpha(Graphics2D g, double a) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, a))));
    }

    /** Draws an image centred at (cx, cy); {@code factor} is on-screen pixels per logical unit. */
    private static void draw(Graphics2D g, SkinSet.Img img, double cx, double cy, double factor, double a, double rot) {
        if (img == null || img.hidden() || a <= 0.003) {
            return;
        }

        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.rotate(rot);
        double s = img.logical() * factor;
        at.scale(s, s);
        at.translate(-img.image().getWidth() / 2.0, -img.image().getHeight() / 2.0);

        alpha(g, a);
        g.drawImage(img.image(), at, null);
        alpha(g, 1);
    }

    // How far the next digit is pulled back over the previous one, in skin units, per glyph prefix. Null when the skin
    // does not say; a slight overlap then keeps the digits from looking spaced out.
    private Double overlapOf(String prefix) {
        if (prefix.equals(skin.iniValue("hitcircleprefix") == null ? "default" : skin.iniValue("hitcircleprefix"))) {
            return skin.iniNumber("hitcircleoverlap");
        }

        if (prefix.equals(scorePrefix())) {
            return skin.iniNumber("scoreoverlap");
        }

        if (prefix.equals(comboPrefix())) {
            return skin.iniNumber("combooverlap");
        }

        return null;
    }

    private String scorePrefix() {
        String v = skin.iniValue("scoreprefix");
        return v == null || v.isBlank() ? "score" : v;
    }

    private String comboPrefix() {
        String v = skin.iniValue("comboprefix");
        return v == null || v.isBlank() ? "score" : v;
    }

    private String circlePrefix() {
        String v = skin.iniValue("hitcircleprefix");
        return v == null || v.isBlank() ? "default" : v;
    }

    private double advance(SkinSet.Img img, double factor, Double overlap) {
        return overlap == null ? img.w() * factor * 0.94 : (img.w() - overlap) * factor;
    }

    /** The width a number takes on screen: the pen advances of all digits but the last, plus the last one in full. */
    private double numberWidth(String text, String prefix, double factor) {
        Double overlap = overlapOf(prefix);
        double total = 0;
        SkinSet.Img last = null;

        for (char c : text.toCharArray()) {
            SkinSet.Img img = glyph(prefix, c);

            if (img == null) {
                continue;
            }

            if (last != null) {
                total += advance(last, factor, overlap);
            }

            last = img;
        }

        return last == null ? total : total + last.w() * factor;
    }

    // numbers from the skin: "score-N", "combo-N", or "default-N" for the numbers on circles
    private void drawNumber(Graphics2D g, String text, String prefix, double x, double y, double factor, double a,
            boolean rightAligned) {
        Double overlap = overlapOf(prefix);
        double cx = rightAligned ? x - numberWidth(text, prefix, factor) : x;

        for (char c : text.toCharArray()) {
            SkinSet.Img img = glyph(prefix, c);

            if (img == null) {
                continue;
            }

            double w = advance(img, factor, overlap);
            double drawW = img.w() * factor;
            // the picture is centred on its own width, the pen moves on by the (possibly shorter) advance
            draw(g, img, cx + drawW / 2, y, factor, a, 0);
            cx += w;
        }
    }

    /** Like {@link #drawNumber} but sized by the on-screen height of a digit, in pixels. */
    private void drawNumberH(Graphics2D g, String text, String prefix, double x, double y, double heightPx, double a,
            boolean rightAligned) {
        SkinSet.Img zero = glyph(prefix, '0');

        if (zero == null) {
            return;
        }

        drawNumber(g, text, prefix, x, y, heightPx / zero.h(), a, rightAligned);
    }

    private SkinSet.Img glyph(String prefix, char c) {
        if (c >= '0' && c <= '9') {
            return skin.get(prefix + "-" + c);
        }

        return switch (c) {
            case '.' -> skin.get(prefix + "-dot");
            case ',' -> skin.get(prefix + "-comma");
            case '%' -> skin.get(prefix + "-percent");
            case 'x' -> skin.get(prefix + "-x");
            default -> null;
        };
    }

    private Color comboColor(OsuMap.Obj o) {
        int own = skin.comboColorCount();

        if (!map.ownColors && own > 0) {
            return skin.comboColor(o.comboIndex % own);
        }

        return map.comboColors.get(o.comboIndex % map.comboColors.size());
    }

    // ------------------------------------------------------------------ gameplay

    public void renderGameplay(BufferedImage target, double t) {
        Graphics2D g = target.createGraphics();
        hints(g);
        g.scale(SCALE, SCALE);
        g.drawImage(background, 0, 0, W, H, null);

        double[] pause = breakAt(t);
        double pauseAmount = pause == null ? 0 : breakAmount(pause, t);

        if (pauseAmount > 0) {
            alpha(g, pauseAmount);
            g.drawImage(brightBackground, 0, 0, W, H, null);
            alpha(g, 1);
        }

        drawFollowPoints(g, t);

        List<OsuMap.Obj> objs = map.objects;
        // later objects are drawn first so earlier ones lie on top of them
        int last = firstAfter(t + sim.preempt);

        for (int i = last - 1; i >= 0; i--) {
            OsuMap.Obj o = objs.get(i);
            PlaySim.Result r = sim.results[i];
            double gone = r.time + (o.type == OsuMap.CIRCLE ? 300 : 200);

            if (o.type != OsuMap.CIRCLE) {
                gone = Math.max(gone, o.endTime + 250);
            }

            if (t > gone) {
                continue;
            }

            switch (o.type) {
                case OsuMap.CIRCLE -> drawCircle(g, o, r, t);
                case OsuMap.SLIDER -> drawSlider(g, i, o, r, t);
                default -> drawSpinner(g, o, r, t);
            }
        }

        drawJudgements(g, t);

        if (pause != null && pauseAmount > 0) {
            drawBreak(g, t, pause, pauseAmount);
        }

        drawCursor(g, t);
        drawKeys(g, t);
        drawHitError(g, t);
        drawMods(g, t);
        drawHud(g, t);
        g.dispose();
    }

    private int firstAfter(double time) {
        int lo = 0;
        int hi = map.objects.size();

        while (lo < hi) {
            int mid = (lo + hi) >>> 1;

            if (map.objects.get(mid).time <= time) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        return lo;
    }

    private double fadeInAlpha(OsuMap.Obj o, double t) {
        return Math.max(0, Math.min(1, (t - (o.time - sim.preempt)) / sim.fadeIn));
    }

    private void drawCircleSprite(Graphics2D g, OsuMap.Obj o, double cx, double cy, double a, double scale) {
        drawCircleSprite(g, o, cx, cy, a, scale, false);
    }

    private void drawCircleSprite(Graphics2D g, OsuMap.Obj o, double cx, double cy, double a, double scale, boolean sliderHead) {
        Color col = comboColor(o);
        boolean own = sliderHead && skin.has("sliderstartcircle");
        String base = own ? "sliderstartcircle" : "hitcircle";
        String overlay = own && skin.has("sliderstartcircleoverlay") ? "sliderstartcircleoverlay" : "hitcircleoverlay";
        boolean overlayAbove = skin.iniFlag("hitcircleoverlayabovenumber", true);
        double numberFactor = k * scale * 0.8;
        String text = Integer.toString(o.comboNumber);

        draw(g, skin.tinted(base, col), cx, cy, k * scale, a, 0);

        if (overlayAbove) {
            drawNumber(g, text, circlePrefix(), cx - numberWidth(text, circlePrefix(), numberFactor) / 2, cy, numberFactor, a,
                    false);
            draw(g, skin.get(overlay), cx, cy, k * scale, a, 0);
        } else {
            draw(g, skin.get(overlay), cx, cy, k * scale, a, 0);
            drawNumber(g, text, circlePrefix(), cx - numberWidth(text, circlePrefix(), numberFactor) / 2, cy, numberFactor, a,
                    false);
        }
    }

    private void drawApproach(Graphics2D g, OsuMap.Obj o, double cx, double cy, double t, double a) {
        double p = (o.time - t) / sim.preempt;

        if (p <= 0 || p > 1) {
            return;
        }

        draw(g, skin.tinted("approachcircle", comboColor(o)), cx, cy, k * (1 + 3 * p), Math.min(1, a) * 0.95, 0);
    }

    private void drawCircle(Graphics2D g, OsuMap.Obj o, PlaySim.Result r, double t) {
        if (t < o.time - sim.preempt) {
            return;
        }

        double cx = sx(o.x);
        double cy = sy(o.y);
        double hitAt = r.result > 0 ? r.time : o.time + sim.hitWindow50();

        if (t < hitAt) {
            double a = fadeInAlpha(o, t);
            drawCircleSprite(g, o, cx, cy, a, 1);
            drawApproach(g, o, cx, cy, t, a);
        } else if (r.result > 0) {
            double d = Math.min(1, (t - hitAt) / 240);
            drawCircleSprite(g, o, cx, cy, 1 - d, 1 + 0.4 * d);
        } else {
            double d = Math.min(1, (t - hitAt) / 120);
            drawCircleSprite(g, o, cx, cy, 1 - d, 1);
        }
    }

    private Path2D body(int index, OsuMap.Obj o) {
        return bodyCache.computeIfAbsent(index, i -> {
            Path2D p = new Path2D.Double();

            for (int n = 0; n < o.px.length; n++) {
                if (n == 0) {
                    p.moveTo(sx(o.px[n]), sy(o.py[n]));
                } else {
                    p.lineTo(sx(o.px[n]), sy(o.py[n]));
                }
            }

            return p;
        });
    }

    private void drawSlider(Graphics2D g, int index, OsuMap.Obj o, PlaySim.Result r, double t) {
        if (t < o.time - sim.preempt) {
            return;
        }

        double a = fadeInAlpha(o, t);

        if (t > o.endTime) {
            a *= Math.max(0, 1 - (t - o.endTime) / 150);
        }

        if (a <= 0) {
            return;
        }

        Color col = comboColor(o);
        double widthPx = 2 * sim.radius * PF;
        Path2D path = body(index, o);

        alpha(g, a);
        g.setStroke(new BasicStroke((float) (widthPx * 0.98), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color border = skin.iniColor("sliderborder");
        Color track = skin.iniColor("slidertrackoverride");
        g.setColor(border != null ? border : new Color(235, 235, 245));
        g.draw(path);
        g.setStroke(new BasicStroke((float) (widthPx * 0.82), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(track != null ? track : new Color(col.getRed() / 4 + 8, col.getGreen() / 4 + 8, col.getBlue() / 4 + 12));
        g.draw(path);
        alpha(g, 1);

        // ticks that the ball has not crossed yet
        double progress = (t - o.time) / o.spanDuration;
        int spanIdx = (int) Math.max(0, Math.min(o.slides - 1, Math.floor(progress)));
        double inSpan = Math.max(0, Math.min(1, progress - spanIdx));
        double dir = (spanIdx & 1) == 0 ? 1 : -1;

        for (double frac : o.tickFractions) {
            boolean passed = t >= o.time && (dir > 0 ? inSpan >= frac : inSpan >= 1 - frac);

            if (passed) {
                continue;
            }

            double[] p = o.pointAtFraction(frac);
            draw(g, skin.get("sliderscorepoint"), sx(p[0]), sy(p[1]), k, a, 0);
        }

        // reverse arrows
        int n = o.px.length;

        if (o.slides > 1 && n >= 2) {
            boolean endArrow = false;
            boolean startArrow = false;

            for (int e = Math.max(0, spanIdx); e < o.slides - 1; e++) {
                if ((e & 1) == 0) {
                    endArrow = true;
                } else {
                    startArrow = true;
                }
            }

            if (endArrow) {
                double ang = Math.atan2(o.py[n - 2] - o.py[n - 1], o.px[n - 2] - o.px[n - 1]);
                draw(g, skin.get("reversearrow"), sx(o.px[n - 1]), sy(o.py[n - 1]), k, a, ang);
            }

            if (startArrow) {
                double ang = Math.atan2(o.py[1] - o.py[0], o.px[1] - o.px[0]);
                draw(g, skin.get("reversearrow"), sx(o.px[0]), sy(o.py[0]), k, a, ang + Math.PI);
            }
        }

        // head circle
        double headAt = r.headHit ? r.headTime : o.time + sim.hitWindow50();
        double hx = sx(o.x);
        double hy = sy(o.y);

        if (t < headAt) {
            double ha = fadeInAlpha(o, t);
            drawCircleSprite(g, o, hx, hy, ha, 1, true);
            drawApproach(g, o, hx, hy, t, ha);
        } else if (t < headAt + 240) {
            double d = (t - headAt) / 240;
            drawCircleSprite(g, o, hx, hy, 1 - d, 1 + (r.headHit ? 0.4 * d : 0), true);
        }

        // ball and follow circle
        if (t >= o.time && t <= o.endTime) {
            double[] b = o.ballAt(progress);
            double bx = sx(b[0]);
            double by = sy(b[1]);
            draw(g, skin.get("sliderfollowcircle"), bx, by, k, 0.9, 0);
            boolean tint = skin.iniFlag("allowsliderballtint", false);
            draw(g, tint ? skin.tinted("sliderb0", comboColor(o)) : skin.get("sliderb0"), bx, by, k, 1, 0);
        }
    }

    /** Scale factor that makes {@code img} {@code heightPx} tall on screen. */
    private static double fitHeight(SkinSet.Img img, double heightPx) {
        return heightPx / img.h();
    }

    private void drawSpinner(Graphics2D g, OsuMap.Obj o, PlaySim.Result r, double t) {
        if (t < o.time - 400 || t > o.endTime + 200) {
            return;
        }

        double a = t < o.time ? (t - (o.time - 400)) / 400 : t > o.endTime ? 1 - (t - o.endTime) / 200 : 1;
        double progress = Math.max(0, Math.min(1, (t - o.time) / (o.endTime - o.time)));
        double cx = W / 2.0;
        double cy = H / 2.0 + 10;

        // a full-screen backdrop when the skin has one, then the spinning disc, about 560px tall
        SkinSet.Img backdrop = skin.get("spinner-background");

        if (backdrop != null && !backdrop.hidden()) {
            alpha(g, a * 0.9);
            g.drawImage(backdrop.image(), 0, 0, W, H, null);
            alpha(g, 1);
        }

        SkinSet.Img circle = skin.get("spinner-circle");

        if (circle != null) {
            double f = fitHeight(circle, 560);
            draw(g, skin.get("spinner-bottom"), cx, cy, f, a * 0.8, 0);
            draw(g, circle, cx, cy, f, a, progress * r.spun * 2 * Math.PI);
            draw(g, skin.get("spinner-middle"), cx, cy, f, a, 0);

            SkinSet.Img middle2 = skin.get("spinner-middle2");

            if (middle2 != null && skin.has("spinner-middle2")) {
                draw(g, middle2, cx, cy, fitHeight(middle2, 560), a * 0.8, 0);
            }

            SkinSet.Img approach = skin.get("spinner-approachcircle");

            if (approach != null) {
                draw(g, approach, cx, cy, f * (1 - 0.92 * progress), a, 0);
            }
        }

        SkinSet.Img spin = skin.get("spinner-spin");

        if (spin != null && t < o.time + 600) {
            draw(g, spin, cx, H - 120, 420.0 / spin.w(), a, 0);
        }
    }

    private void drawFollowPoints(Graphics2D g, double t) {
        SkinSet.Img img = skin.get("followpoint");

        if (img == null) {
            return;
        }

        List<OsuMap.Obj> objs = map.objects;
        int last = firstAfter(t + sim.preempt);

        for (int i = Math.max(1, last - 40); i < Math.min(objs.size(), last + 1); i++) {
            OsuMap.Obj a = objs.get(i - 1);
            OsuMap.Obj b = objs.get(i);

            if (b.newCombo || a.type == OsuMap.SPINNER || b.type == OsuMap.SPINNER) {
                continue;
            }

            double ax = a.type == OsuMap.SLIDER ? a.px[a.px.length - 1] : a.x;
            double ay = a.type == OsuMap.SLIDER ? a.py[a.py.length - 1] : a.y;
            double dx = b.x - ax;
            double dy = b.y - ay;
            double dist = Math.hypot(dx, dy);
            double start = 2 * sim.radius * 0.5;

            if (dist < sim.radius * 2) {
                continue;
            }

            double ang = Math.atan2(dy, dx);
            double spacing = img.w() > 40 ? img.w() * 0.9 : 32 * 0.8;
            int dots = Math.max(1, (int) ((dist - sim.radius * 2) / spacing));

            for (int d = 0; d < dots; d++) {
                double f = (start + (d + 0.5) * ((dist - 2 * start) / Math.max(1, dots))) / dist;
                double tf = a.endTime + (b.time - a.endTime) * f;
                double fadeStart = tf - sim.preempt * 0.55;
                double al = Math.min(1, (t - fadeStart) / 150);

                if (t >= tf) {
                    al = Math.min(al, Math.max(0, 1 - (t - tf) / 150));
                }

                if (al > 0) {
                    draw(g, img, sx(ax + dx * f), sy(ay + dy * f), PF, al * 0.8, ang);
                }
            }
        }
    }

    private void drawJudgements(Graphics2D g, double t) {
        int hi = firstAfter(t + 1);

        for (int i = Math.max(0, hi - 40); i < hi; i++) {
            OsuMap.Obj o = map.objects.get(i);
            PlaySim.Result r = sim.results[i];
            double at = o.type == OsuMap.CIRCLE ? r.time : o.endTime;
            double d = t - at;

            if (d < 0 || d > 500 || o.type == OsuMap.SPINNER) {
                continue;
            }

            String name = r.result == 100 ? "hit100" : r.result == 50 ? "hit50" : r.result == 0 ? "hit0" : null;

            if (name == null) {
                continue; // a 300 is not announced, like in the default skin
            }

            // a skin that animates the popup ships name-0.png, which is what stable draws first
            SkinSet.Img pop = skin.get(name + "-0");

            if (pop == null) {
                pop = skin.get(name);
            }

            double[] p = o.type == OsuMap.SLIDER ? new double[] { o.px[o.px.length - 1], o.py[o.py.length - 1] }
                    : new double[] { o.x, o.y };
            double fall = r.result == 0 ? d * 0.04 : 0;
            double popup = d < 80 ? 0.85 + 0.15 * d / 80 : 1;
            draw(g, pop, sx(p[0]), sy(p[1]) + 20 + fall, PF * 0.6 * popup, d < 350 ? 1 : 1 - (d - 350) / 150, 0);
        }
    }

    private void drawCursor(Graphics2D g, double t) {
        SkinSet.Img trail = skin.get("cursortrail");
        SkinSet.Img cursor = skin.get("cursor");
        // the centre piece is only drawn when the server skin itself has one
        SkinSet.Img middle = skin.has("cursormiddle") ? skin.get("cursormiddle") : null;

        for (int i = 14; i >= 1; i--) {
            float[] p = sim.cursorAt(t - i * 11.0 * sim.rate);
            draw(g, trail, sx(p[0]), sy(p[1]), PF * 0.65, (1 - i / 15.0) * 0.45, 0);
        }

        float[] c = sim.cursorAt(t);
        boolean down = false;

        for (OsrReplay.Frame f : sim.replay.frames) {
            if (f.time() > t) {
                break;
            }

            down = f.keys() != 0;
        }

        double scale = PF * 0.55 * (down && skin.iniFlag("cursorexpand", true) ? 1.15 : 1.0);
        draw(g, cursor, sx(c[0]), sy(c[1]), scale, 1, 0);
        draw(g, middle, sx(c[0]), sy(c[1]), scale, 1, 0);
    }

    // ------------------------------------------------------------------ keys, hit errors, mods

    private static boolean keyOn(int raw, int key) {
        return switch (key) {
            case 0 -> (raw & 4) != 0; // K1
            case 1 -> (raw & 8) != 0; // K2
            case 2 -> (raw & 1) != 0 && (raw & 4) == 0; // M1 (a K1 press also raises the M1 bit)
            default -> (raw & 2) != 0 && (raw & 8) == 0; // M2
        };
    }

    private void buildKeyPresses() {
        for (int key = 0; key < 4; key++) {
            java.util.List<Double> times = new java.util.ArrayList<>();
            boolean down = false;

            for (OsrReplay.Frame f : sim.replay.frames) {
                boolean now = keyOn(f.raw(), key);

                if (now && !down) {
                    times.add((double) f.time());
                }

                down = now;
            }

            pressTimes[key] = times.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    private OsrReplay.Frame frameAt(double t) {
        List<OsrReplay.Frame> f = sim.replay.frames;
        int lo = 0;
        int hi = f.size() - 1;

        if (f.isEmpty() || f.get(0).time() > t) {
            return null;
        }

        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;

            if (f.get(mid).time() <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return f.get(lo);
    }

    private static int countUpTo(double[] times, double t) {
        int lo = 0;
        int hi = times.length;

        while (lo < hi) {
            int mid = (lo + hi) >>> 1;

            if (times[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        return lo;
    }

    // The four keys at the right edge: lit while held, with how many times each has been pressed.
    private void drawKeys(Graphics2D g, double t) {
        OsrReplay.Frame fr = frameAt(t);
        int raw = fr == null ? 0 : fr.raw();
        String[] labels = { "K1", "K2", "M1", "M2" };
        SkinSet.Img panel = skin.get("inputoverlay-background");
        SkinSet.Img key = skin.get("inputoverlay-key");
        double midY = H / 2.0 + 10;
        double keysX = W - 48;

        // The panel is the skin's artwork turned upright: label bar on its left, the pattern the keys sit on at its
        // right. It lies behind the four keys, which are centred on the pattern.
        if (panel != null && skin.has("inputoverlay-background")) {
            double perPixel = 300.0 / panel.image().getHeight();
            double w = panel.image().getWidth() * perPixel;
            double left = W - 6 - w;
            draw(g, panel, left + w / 2, midY, perPixel / panel.logical(), 0.95, 0);
            keysX = left + w * 0.6325;
        }

        if (key == null) {
            return;
        }

        Color lit = new Color(255, 196, 64);
        SkinSet.Img litKey = skin.recolored("inputoverlay-key", lit);

        for (int i = 0; i < 4; i++) {
            boolean on = keyOn(raw, i);
            double cy = midY + (i - 1.5) * 66;
            double cx = keysX;
            draw(g, on ? litKey : key, cx, cy, 56.0 / key.h(), on ? 1 : 0.9, 0);

            g.setColor(on ? new Color(30, 24, 10) : new Color(235, 235, 245));
            Font small = F_REG.deriveFont(Font.BOLD, 11f);
            Font big = F_REG.deriveFont(Font.BOLD, 17f);
            FontMetrics fs = g.getFontMetrics(small);
            FontMetrics fb = g.getFontMetrics(big);
            String n = Integer.toString(countUpTo(pressTimes[i], t));
            // the two lines are one block, centred on the key both ways (text is positioned by its baseline)
            double gap = 3;
            double blockH = fs.getAscent() + gap + fb.getAscent();
            double top = cy - blockH / 2;
            g.setFont(small);
            g.drawString(labels[i], (float) (cx - fs.stringWidth(labels[i]) / 2.0), (float) (top + fs.getAscent()));
            g.setFont(big);
            g.drawString(n, (float) (cx - fb.stringWidth(n) / 2.0), (float) (top + fs.getAscent() + gap + fb.getAscent()));
        }
    }

    // The hit error bar: how early or late every hit was, against the 300 / 100 / 50 windows, newest ticks brightest.
    private void drawHitError(Graphics2D g, double t) {
        double rate = sim.rate;
        double w50 = sim.hitWindow50() / rate;
        double w100 = sim.hitWindow100() / rate;
        double w300 = sim.hitWindow300() / rate;
        double width = 360;
        double perMs = width / (2 * w50);
        double cx = W / 2.0;
        double cy = H - 34;
        Color c300 = new Color(84, 170, 255);
        Color c100 = new Color(110, 228, 90);
        Color c50 = new Color(255, 205, 70);

        alpha(g, 0.35);
        g.setColor(c50);
        g.fillRoundRect((int) (cx - w50 * perMs), (int) (cy - 4), (int) (2 * w50 * perMs), 8, 4, 4);
        g.setColor(c100);
        g.fillRect((int) (cx - w100 * perMs), (int) (cy - 4), (int) (2 * w100 * perMs), 8);
        g.setColor(c300);
        g.fillRect((int) (cx - w300 * perMs), (int) (cy - 4), (int) (2 * w300 * perMs), 8);
        alpha(g, 0.9);
        g.setColor(Color.WHITE);
        g.fillRect((int) cx - 1, (int) (cy - 9), 2, 18);
        alpha(g, 1);

        int hi = firstAfter(t + 400);
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        double recent = 0;
        int recentCount = 0;

        for (int i = hi - 1; i >= 0; i--) {
            PlaySim.Result r = sim.results[i];

            if (Double.isNaN(r.error) || r.headTime > t) {
                continue;
            }

            double err = r.error / rate;
            sum += err;
            sumSq += err * err;
            count++;

            if (recentCount < 20) {
                recent += err;
                recentCount++;
            }

            double age = t - r.headTime;

            if (age <= 4500) {
                double a = age < 2800 ? 1 : 1 - (age - 2800) / 1700;
                double ae = Math.abs(r.error);
                alpha(g, a * 0.95);
                g.setColor(ae <= sim.hitWindow300() ? c300 : ae <= sim.hitWindow100() ? c100 : c50);
                g.fillRect((int) (cx + err * perMs - 1), (int) (cy - 11), 3, 22);
                alpha(g, 1);
            }
        }

        // the arrow shows where the last twenty hits average out
        if (recentCount > 0) {
            double ax = cx + Math.max(-w50, Math.min(w50, recent / recentCount)) * perMs;
            Path2D tri = new Path2D.Double();
            tri.moveTo(ax - 6, cy - 22);
            tri.lineTo(ax + 6, cy - 22);
            tri.lineTo(ax, cy - 13);
            tri.closePath();
            g.setColor(new Color(255, 255, 255, 220));
            g.fill(tri);
        }

        // unstable rate: ten times the standard deviation of the hit errors so far
        if (count > 3) {
            double mean = sum / count;
            double ur = Math.sqrt(Math.max(0, sumSq / count - mean * mean)) * 10;
            g.setFont(F_REG.deriveFont(Font.BOLD, 14f));
            g.setColor(new Color(255, 255, 255, 190));
            g.drawString(String.format(Locale.ROOT, "UR %.0f", ur), (int) (cx + width / 2 + 18), (int) (cy + 5));
        }
    }

    // The mods of the play, shown for the first seconds and then faded out.
    private void drawMods(Graphics2D g, double t) {
        double elapsed = (t - clipStart) / sim.rate;

        if (elapsed < 0 || elapsed > 4500) {
            return;
        }

        double a = Math.min(1, elapsed / 500) * (elapsed > 3600 ? 1 - (elapsed - 3600) / 900 : 1);
        java.util.List<String> names = ModsText.iconNames(sim.replay.mods);
        double size = 84;
        double step = 64;
        double total = size + (names.size() - 1) * step;
        double x0 = W - 24 - total;

        for (int i = 0; i < names.size(); i++) {
            SkinSet.Img icon = skin.getDefault("selection-mod-" + names.get(i));
            icon = skin.get("selection-mod-" + names.get(i)) != null ? skin.get("selection-mod-" + names.get(i)) : icon;
            draw(g, icon, x0 + size / 2 + i * step, 150, size / (icon == null ? 1 : icon.h()), a, 0);
        }
    }

    // ------------------------------------------------------------------ breaks

    /** The break period that {@code t} falls in, if it is a real one (the game ignores very short gaps). */
    private double[] breakAt(double t) {
        for (double[] b : map.breaks) {
            if (b[1] - b[0] >= 650 && t >= b[0] && t <= b[1]) {
                return b;
            }
        }

        return null;
    }

    private static double breakAmount(double[] b, double t) {
        return Math.max(0, Math.min(1, Math.min((t - b[0]) / 350.0, (b[1] - t) / 350.0)));
    }

    // The rest between two parts: the background lights up, the section is marked passed or failed, how the play stands
    // is shown, a bar counts the break down, and arrows blink shortly before the notes come back.
    private void drawBreak(Graphics2D g, double t, double[] b, double amount) {
        PlaySim.Tick tick = sim.tickAt(t);
        double cx = W / 2.0;
        double cy = H / 2.0;

        SkinSet.Img section = skin.get(tick.health >= 0.5 ? "section-pass" : "section-fail");

        if (section != null) {
            double factor = 300.0 / section.image().getWidth() / section.logical();
            draw(g, section, cx, cy - 110, factor, amount, 0);
        }

        long shown = Math.round(info.score() * tick.scoreShare);
        String[] captions = { "ACCURACY", "COMBO", "SCORE" };
        String[] values = { String.format(Locale.ROOT, "%.2f%%", tick.accuracy * 100), tick.combo + "x",
                String.format(Locale.ROOT, "%08d", shown) };
        double[] xs = { cx - 330, cx, cx + 330 };

        g.setFont(F_REG.deriveFont(Font.BOLD, 15f));
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < 3; i++) {
            alpha(g, amount * 0.75);
            g.setColor(Color.WHITE);
            g.drawString(captions[i], (int) (xs[i] - fm.stringWidth(captions[i]) / 2.0), (int) (cy + 76));
            alpha(g, 1);
            drawNumberCentered(g, values[i], xs[i], cy + 116, 40, amount);
        }

        // remaining time
        double left = Math.max(0, Math.min(1, (b[1] - t) / (b[1] - b[0])));
        int barW = 520;
        int barX = (int) (cx - barW / 2.0);
        int barY = (int) (cy + 186);
        alpha(g, amount * 0.35);
        g.setColor(Color.WHITE);
        g.fillRoundRect(barX, barY, barW, 8, 8, 8);
        alpha(g, amount * 0.95);
        g.fillRoundRect(barX, barY, (int) (barW * left), 8, 8, 8);
        alpha(g, 1);

        // blinking arrows in the last moment of the break
        double remaining = b[1] - t;
        SkinSet.Img arrow = skin.get("play-warningarrow");

        if (arrow != null && remaining < 1300 && remaining > 0 && ((int) (remaining / 150) & 1) == 0) {
            draw(g, arrow, 150, cy, PF * 0.9, 0.9, 0);
            draw(g, arrow, W - 150, cy, PF * 0.9, 0.9, Math.PI);
        }
    }

    private void drawNumberCentered(Graphics2D g, String text, double cx, double y, double heightPx, double a) {
        SkinSet.Img zero = glyph(scorePrefix(), '0');

        if (zero == null) {
            return;
        }

        double factor = heightPx / zero.h();
        drawNumber(g, text, scorePrefix(), cx - numberWidth(text, scorePrefix(), factor) / 2, y, factor, a, false);
    }

    private void drawHud(Graphics2D g, double t) {
        PlaySim.Tick tick = sim.tickAt(t);

        // score and accuracy, top right
        long shown = Math.round(info.score() * tick.scoreShare);
        drawNumberH(g, String.format(Locale.ROOT, "%08d", shown), scorePrefix(), W - 20, 34, 44, 1, true);
        drawNumberH(g, String.format(Locale.ROOT, "%.2f%%", tick.accuracy * 100), scorePrefix(), W - 20, 80, 26, 0.9, true);

        // combo, bottom left
        drawNumberH(g, tick.combo + "x", comboPrefix(), 24, H - 44, 52, 1, false);

        // health: the skin's frame, with the coloured bar laid in it and cut to the health that is left
        SkinSet.Img bg = skin.get("scorebar-bg");
        SkinSet.Img col = skin.get("scorebar-colour");

        if (bg != null) {
            double s = 520.0 / bg.image().getWidth();
            alpha(g, 0.95);
            g.drawImage(bg.image(), 0, 0, (int) (bg.image().getWidth() * s), (int) (bg.image().getHeight() * s), null);
            alpha(g, 1);

            if (col != null) {
                int srcW = Math.max(1, Math.min(col.image().getWidth(), (int) (col.image().getWidth() * tick.health)));
                g.drawImage(col.image().getSubimage(0, 0, srcW, col.image().getHeight()), (int) (24 * s), (int) (24 * s),
                        (int) (srcW * s), (int) (col.image().getHeight() * s), null);
            }
        }
    }

    // ------------------------------------------------------------------ results

    public void renderResults(BufferedImage target, double progress) {
        Graphics2D g = target.createGraphics();
        hints(g);
        g.scale(SCALE, SCALE);
        g.drawImage(background, 0, 0, W, H, null);

        double a = Math.max(0, Math.min(1, progress * 3));
        alpha(g, a);

        // title block
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(0, 0, W, 96);
        g.setColor(Color.WHITE);
        g.setFont(F_BOLD);
        g.drawString(map.artist + " - " + map.title + " [" + map.version + "]", 28, 38);
        g.setFont(F_REG);
        g.setColor(new Color(225, 225, 238));
        g.drawString("Beatmap by " + map.creator, 28, 64);
        g.drawString("Played by " + info.player() + (info.playedAt().isBlank() ? "" : " on " + info.playedAt()), 28, 88);

        // score panel
        int px = 56;
        int py = 128;
        int pw = 600;
        int ph = 520;
        g.setColor(new Color(10, 10, 20, 190));
        g.fillRoundRect(px, py, pw, ph, 22, 22);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(px, py, pw, ph, 22, 22);

        g.setFont(F_REG.deriveFont(Font.BOLD, 16f));
        g.setColor(new Color(255, 255, 255, 150));
        g.drawString("SCORE", px + 32, py + 40);
        drawNumberH(g, String.format(Locale.ROOT, "%08d", info.score()), scorePrefix(), px + 32, py + 92, 58, 1, false);

        String[] icons = { "hit300", "hit100", "hit50" };
        String[] icons2 = { "hit300g", "hit100k", "hit0" };
        int[] counts = { info.n300(), info.n100(), info.n50() };
        int[] counts2 = { info.nGeki(), info.nKatu(), info.nMiss() };

        for (int row = 0; row < 3; row++) {
            double y = py + 178 + row * 70;
            drawIconH(g, icons[row], px + 32, y, 46);
            drawNumberH(g, counts[row] + "x", scorePrefix(), px + 150, y, 34, 1, false);
            drawIconH(g, icons2[row], px + 330, y, 46);
            drawNumberH(g, counts2[row] + "x", scorePrefix(), px + 448, y, 34, 1, false);
        }

        double by = py + 440;
        g.setColor(new Color(255, 255, 255, 40));
        g.drawLine(px + 28, py + 395, px + pw - 28, py + 395);
        g.setFont(F_REG.deriveFont(Font.BOLD, 15f));
        g.setColor(new Color(255, 255, 255, 150));
        g.drawString("MAX COMBO", px + 32, py + 420);
        g.drawString("ACCURACY", px + 330, py + 420);
        drawNumberH(g, info.maxCombo() + "x", scorePrefix(), px + 32, by + 22, 38, 1, false);
        drawNumberH(g, String.format(Locale.ROOT, "%.2f%%", info.accuracy() * 100), scorePrefix(), px + 330, by + 22, 38, 1, false);

        // grade, pp and mods on the right
        double gs = 0.85 + 0.15 * Math.min(1, progress * 2);
        SkinSet.Img rank = skin.get("ranking-" + info.grade());

        if (rank != null) {
            // fits a 560 x 400 box: the skin's rank artwork is a portrait with the letter beside it, so it is wide
            double perPixel = Math.min(400.0 / rank.image().getHeight(), 560.0 / rank.image().getWidth()) * gs;
            draw(g, rank, 960, 340, perPixel / rank.logical(), a, 0);
        }

        g.setFont(F_BOLD.deriveFont(48f));
        g.setColor(new Color(255, 102, 170));
        String pp = String.format(Locale.ROOT, "%.0fpp", info.pp());
        FontMetrics fm = g.getFontMetrics();
        g.drawString(pp, 960 - fm.stringWidth(pp) / 2, 568);

        if (!info.mods().isBlank()) {
            g.setFont(F_BOLD.deriveFont(30f));
            g.setColor(Color.WHITE);
            fm = g.getFontMetrics();
            g.drawString(info.mods(), 960 - fm.stringWidth(info.mods()) / 2, 616);
        }

        alpha(g, 1);
        g.dispose();
    }

    /** An icon from the skin, left aligned at x, centred on y, scaled to {@code heightPx}. */
    private void drawIconH(Graphics2D g, String name, double x, double y, double heightPx) {
        // the server skin hides some of these (it announces no 300); the results list needs all six
        SkinSet.Img img = skin.getDefault(name);

        if (img == null) {
            return;
        }

        double factor = heightPx / img.h();
        draw(g, img, x + img.w() * factor / 2, y, factor, 1, 0);
    }
}
