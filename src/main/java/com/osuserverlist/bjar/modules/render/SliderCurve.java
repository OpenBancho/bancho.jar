package com.osuserverlist.bjar.modules.render;

import java.util.ArrayList;
import java.util.List;

/** Turns the control points of a slider into a polyline. Points are {x, y} pairs in playfield units. */
final class SliderCurve {

    private SliderCurve() {
    }

    static List<double[]> flatten(char kind, List<double[]> pts) {
        if (pts.size() < 2) {
            return new ArrayList<>(pts);
        }

        return switch (kind) {
            case 'L' -> new ArrayList<>(pts);
            case 'P' -> pts.size() == 3 ? perfect(pts) : bezier(pts);
            case 'C' -> catmull(pts);
            default -> bezier(pts);
        };
    }

    // A bezier slider is several curves joined where a point is written twice.
    private static List<double[]> bezier(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        List<double[]> run = new ArrayList<>();

        for (int i = 0; i < pts.size(); i++) {
            run.add(pts.get(i));
            boolean last = i == pts.size() - 1;

            if (last || (i + 1 < pts.size() && same(pts.get(i), pts.get(i + 1)))) {
                if (run.size() == 2) {
                    addAll(out, run);
                } else {
                    addAll(out, sampleBezier(run));
                }

                run = new ArrayList<>();

                if (!last) {
                    // the duplicated point starts the next run
                    continue;
                }
            }
        }

        return out;
    }

    private static boolean same(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    private static void addAll(List<double[]> out, List<double[]> add) {
        for (double[] p : add) {
            if (out.isEmpty() || !same(out.get(out.size() - 1), p)) {
                out.add(p);
            }
        }
    }

    private static List<double[]> sampleBezier(List<double[]> c) {
        double len = 0;

        for (int i = 1; i < c.size(); i++) {
            len += Math.hypot(c.get(i)[0] - c.get(i - 1)[0], c.get(i)[1] - c.get(i - 1)[1]);
        }

        int steps = Math.max(8, Math.min(200, (int) (len / 4)));
        List<double[]> out = new ArrayList<>();
        int n = c.size();
        double[] bx = new double[n];
        double[] by = new double[n];

        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;

            for (int i = 0; i < n; i++) {
                bx[i] = c.get(i)[0];
                by[i] = c.get(i)[1];
            }

            for (int k = 1; k < n; k++) {
                for (int i = 0; i < n - k; i++) {
                    bx[i] = bx[i] + (bx[i + 1] - bx[i]) * t;
                    by[i] = by[i] + (by[i + 1] - by[i]) * t;
                }
            }

            out.add(new double[] { bx[0], by[0] });
        }

        return out;
    }

    private static List<double[]> perfect(List<double[]> p) {
        double[] a = p.get(0);
        double[] b = p.get(1);
        double[] c = p.get(2);

        double d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));

        // collinear points make no circle: the game falls back to a bezier
        if (Math.abs(d) < 1e-6) {
            return bezier(p);
        }

        double a2 = a[0] * a[0] + a[1] * a[1];
        double b2 = b[0] * b[0] + b[1] * b[1];
        double c2 = c[0] * c[0] + c[1] * c[1];
        double cx = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d;
        double cy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d;
        double r = Math.hypot(a[0] - cx, a[1] - cy);

        double t0 = Math.atan2(a[1] - cy, a[0] - cx);
        double t1 = Math.atan2(b[1] - cy, b[0] - cx);
        double t2 = Math.atan2(c[1] - cy, c[0] - cx);

        // The arc runs from a to c through b: take the direction in which b is passed before c is reached.
        double twoPi = 2 * Math.PI;
        double ccwToC = ((t2 - t0) % twoPi + twoPi) % twoPi;
        double ccwToB = ((t1 - t0) % twoPi + twoPi) % twoPi;
        double span = ccwToB <= ccwToC ? ccwToC : ccwToC - twoPi;

        int steps = Math.max(8, (int) (Math.abs(span) * r / 4));
        List<double[]> out = new ArrayList<>();

        for (int i = 0; i <= steps; i++) {
            double t = t0 + span * i / steps;
            out.add(new double[] { cx + r * Math.cos(t), cy + r * Math.sin(t) });
        }

        return out;
    }

    private static List<double[]> catmull(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();

        for (int i = 0; i < pts.size() - 1; i++) {
            double[] p0 = pts.get(Math.max(i - 1, 0));
            double[] p1 = pts.get(i);
            double[] p2 = pts.get(i + 1);
            double[] p3 = pts.get(Math.min(i + 2, pts.size() - 1));

            for (int s = 0; s <= 20; s++) {
                double t = s / 20.0;
                double t2 = t * t;
                double t3 = t2 * t;
                double x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                        + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                double y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                        + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                out.add(new double[] { x, y });
            }
        }

        return out;
    }

    /** Cuts the polyline to {@code length}, or extends its last segment when the curve is shorter. */
    static List<double[]> fitLength(List<double[]> poly, double length) {
        List<double[]> out = new ArrayList<>();

        if (poly.isEmpty()) {
            return out;
        }

        double acc = 0;
        out.add(poly.get(0));

        for (int i = 1; i < poly.size(); i++) {
            double[] a = poly.get(i - 1);
            double[] b = poly.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);

            if (acc + seg >= length) {
                double t = seg <= 0 ? 0 : (length - acc) / seg;
                out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t });
                return out;
            }

            acc += seg;
            out.add(b);
        }

        if (acc < length && out.size() >= 2) {
            double[] a = out.get(out.size() - 2);
            double[] b = out.get(out.size() - 1);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);

            if (seg > 0) {
                double t = (length - acc) / seg;
                out.add(new double[] { b[0] + (b[0] - a[0]) * t, b[1] + (b[1] - a[1]) * t });
            }
        }

        return out;
    }
}
