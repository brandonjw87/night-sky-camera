package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Figures;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.SkyGuide;
import com.brandon.nightsky.processing.SkySolver;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * The constellations' figures (1.10). The table: 88 figures, each named, whose stars are the catalogue's. Placed on
 * pictures through a plate solution: every line follows the sky between two stars as the lens shows it, starts and ends
 * at its stars unless it runs off the picture, and no point comes from beyond where the lens model holds, even with a
 * distortion that turns back on itself (where the plain projection would put stars from behind the camera's view in the
 * picture); the names go inside their figures; and it all survives the report's file.
 * <p>
 * Usage: FiguresTest [folder for pictures of the figures]
 */
public final class FiguresTest {
    static int failed;
    static BrightStars cat;

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        cat = SimRunner.loadCatalog();

        // ---- the table ----
        List<Figures.Figure> all = Figures.all();
        check("88 figures", all.size() == 88, all.size() + "");
        int segments = 0, vertices = 0, matched = 0, unnamed = 0;
        double worst = 0;
        for (Figures.Figure f : all) {
            // Ara and Leo are their own abbreviations
            if (BrightStars.constellation(f.abbr).equals(f.abbr) && !f.abbr.equals("Ara") && !f.abbr.equals("Leo")) unnamed++;
            for (double[][] run : f.runs) {
                segments += run.length - 1;
                for (double[] v : run) {
                    vertices++;
                    double best = Double.MAX_VALUE;
                    for (int k = 0; k < cat.n; k++) best = Math.min(best, Astro.sep(v, cat.vec(k)));
                    best /= Astro.DEG;
                    if (best < 0.02) matched++;
                    else worst = Math.max(worst, best);
                }
            }
        }
        System.out.printf(Locale.US, "table: %d figures, %d lines, %d star points, %d on a catalogue star (within 0.02 deg); "
                + "the farthest of the rest %.2f deg%n", all.size(), segments, vertices, matched, worst);
        check("every figure named", unnamed == 0, unnamed + " not");
        check("700+ lines", segments >= 700, segments + "");
        check("the figures' stars are the catalogue's", matched >= 0.97 * vertices, matched + " of " + vertices);
        check("The Plough's stars", near(Figures.of("UMa"), cat, "Dubhe") && near(Figures.of("UMa"), cat, "Alkaid"), "");

        // ---- placed on pictures, with the sim's lens ----
        CameraModel lens = CameraModel.nominal(4080, 3072, 2875);
        lens.k1 = -0.045;
        lens.k2 = 0.018;
        double[][] targets = {{165, 56, 0}, {83, 2, 25}, {285, 38, -40}, {266, -30, 90}, {0, 89, 0}, {40, 40, 180}};
        String[] names = {"Ursa Major", "Orion", "Lyra", "Scorpius", "Ursa Minor", "Andromeda"};
        for (int t = 0; t < targets.length; t++) {
            SkySolver.Solution sol = solution(lens, targets[t][0], targets[t][1], targets[t][2]);
            ReportCard card = card(sol, 0, 0, 4080, 3072);
            SkyGuide g = card.sky;
            String what = String.format(Locale.US, "RA %.0f Dec %+.0f", targets[t][0], targets[t][1]);
            check(what + ": placed", g != null && g.placed, "");
            if (g == null) continue;
            check(what + ": figures", g.figures.size() >= 15, g.figures.size() + "");
            follows(what, g, sol, 0, 0, 4080, 3072, all);
            SkyGuide.Item it = item(g, names[t]);
            check(what + ": " + names[t] + "'s name inside its figure", it != null && !Double.isNaN(it.lx)
                            && it.lx > 0.03 && it.lx < 0.97 && it.ly > 0.03 && it.ly < 0.97,
                    it == null ? "not in the picture" : it.lx + "," + it.ly);
            // the report's file
            ReportCard back = ReportCard.fromJson(card.toJson());
            check(what + ": the report's file keeps them", same(g, back.sky), back.sky == null ? "no sky" : back.sky.figures.size() + "");
            if (out != null) draw(new File(out, "figures-" + t + ".png"), g, sol, cat, 0, 0, 4080, 3072);
        }

        // ---- a crop of the picture: the figures are the crop's ----
        {
            SkySolver.Solution sol = solution(lens, 165, 56, 0);
            int x0 = 900, y0 = 600, w = 2200, h = 1700;
            ReportCard card = card(sol, x0, y0, w, h);
            follows("crop", card.sky, sol, x0, y0, w, h, all);
        }

        // ---- a lens whose distortion turns back ----
        {
            CameraModel bent = CameraModel.nominal(4080, 3072, 4636);
            bent.k1 = -0.35;
            SkySolver.Solution sol = solution(bent, 165, 56, 0);
            // the plain projection puts catalogue stars from far outside the view in the picture
            int folded = 0;
            for (int k = 0; k < cat.n; k++) {
                double[] b = new double[3];
                double[] v = cat.vec(k);
                com.brandon.nightsky.processing.Rot.apply(com.brandon.nightsky.processing.Rot.transpose(sol.R), v[0], v[1], v[2], b);
                if (b[2] <= 0 || Math.hypot(b[0] / b[2], b[1] / b[2]) < 1.2) continue;
                double[] p = sol.pixel(v);
                if (p != null && p[0] >= 0 && p[0] <= 4080 && p[1] >= 0 && p[1] <= 3072) folded++;
            }
            System.out.printf(Locale.US, "turned-back lens: %d catalogue stars from over 50 deg off the axis project into the picture%n", folded);
            check("the turned-back lens folds stars in (the test means something)", folded > 20, folded + "");
            ReportCard card = card(sol, 0, 0, 4080, 3072);
            follows("turned-back lens", card.sky, sol, 0, 0, 4080, 3072, all);
            // nor are stars from out there named in it
            int wrong = 0, named = 0;
            for (SkyGuide.Item it : card.sky.items) {
                if (!"star".equals(it.kind)) continue;
                named++;
                for (int k = 0; k < cat.n; k++) {
                    if (!cat.name[k].equals(it.name)) continue;
                    double[] b = new double[3];
                    double[] v = cat.vec(k);
                    com.brandon.nightsky.processing.Rot.apply(com.brandon.nightsky.processing.Rot.transpose(sol.R), v[0], v[1], v[2], b);
                    if (b[2] <= 0 || Math.hypot(b[0] / b[2], b[1] / b[2]) > 1.0) wrong++;
                }
            }
            check("turned-back lens: no star from beyond the view named in the picture", wrong == 0 && named > 0, wrong + " of " + named);
            if (out != null) draw(new File(out, "figures-bent.png"), card.sky, sol, cat, 0, 0, 4080, 3072);
        }

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    /**
     * Every line of {@code g} follows the sky: its points, taken back to the sky through the solution, lie on the great
     * circle between two stars of one figure of its constellation (within 0.02 deg), between them; a line that says it
     * starts or ends at a star does; the points stay near the picture.
     */
    static void follows(String what, SkyGuide g, SkySolver.Solution sol, int x0, int y0, int w, int h, List<Figures.Figure> all) {
        int bad = 0, badEnds = 0, far = 0, n = 0;
        String first = "";
        for (SkyGuide.Line l : g.figures) {
            Figures.Figure f = Figures.of(l.c);
            if (f == null) {
                bad++;
                continue;
            }
            int m = l.pts.length / 2;
            double[][] d = new double[m][];
            for (int i = 0; i < m; i++) {
                double u = l.pts[2 * i], v = l.pts[2 * i + 1];
                if (u < -0.051 || u > 1.051 || v < -0.051 || v > 1.051) {
                    // only an end point may be past the margin (it takes the line to the edge)
                    if (i != 0 && i != m - 1) far++;
                }
                d[i] = sol.direction(x0 + u * w, y0 + v * h);
                n++;
            }
            // the figure's line this one lies on
            double bestErr = Double.MAX_VALUE;
            double[] A = null, B = null;
            for (double[][] run : f.runs) {
                for (int k = 0; k + 1 < run.length; k++) {
                    double err = 0;
                    for (double[] p : d) err = Math.max(err, offArc(p, run[k], run[k + 1]));
                    if (err < bestErr) {
                        bestErr = err;
                        A = run[k];
                        B = run[k + 1];
                    }
                }
            }
            if (bestErr > 0.02 * Astro.DEG) {
                bad++;
                if (first.isEmpty()) first = String.format(Locale.US, "%s off by %.3f deg", l.c, bestErr / Astro.DEG);
                continue;
            }
            // its ends at its stars, when it says so (either way round)
            double e0 = Math.min(Astro.sep(d[0], A), Astro.sep(d[0], B)), e1 = Math.min(Astro.sep(d[m - 1], A), Astro.sep(d[m - 1], B));
            if (l.a && e0 > 0.01 * Astro.DEG || l.b && e1 > 0.01 * Astro.DEG) badEnds++;
        }
        System.out.printf(Locale.US, "%s: %d lines, %d points; %d off their figure's lines%s, %d ends not at their stars, %d points far out%n",
                what, g.figures.size(), n, bad, first.isEmpty() ? "" : " (" + first + ")", badEnds, far);
        check(what + ": every line on its figure", bad == 0, bad + "");
        check(what + ": ends at their stars", badEnds == 0, badEnds + "");
        check(what + ": near the picture", far == 0, far + "");
    }

    /** How far direction p is from the great-circle arc a-b (radians): from the circle if it's between them, else from the nearer end. */
    static double offArc(double[] p, double[] a, double[] b) {
        double[] nrm = cross(a, b);
        double nn = Math.sqrt(dot(nrm, nrm));
        if (nn < 1e-12) return Math.min(Astro.sep(p, a), Astro.sep(p, b));
        for (int i = 0; i < 3; i++) nrm[i] /= nn;
        double off = Math.abs(Math.asin(Math.max(-1, Math.min(1, dot(p, nrm)))));
        // between a and b: on the same side of the planes through each end, square to the arc
        double[] na = cross(nrm, a), nb = cross(b, nrm);
        if (dot(p, na) >= -1e-9 && dot(p, nb) >= -1e-9) return off;
        return Math.min(Astro.sep(p, a), Astro.sep(p, b));
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** A solution with the camera's axis at RA, Dec (degrees) and the picture's up turned {@code rot} degrees from north. */
    static SkySolver.Solution solution(CameraModel m, double ra, double dec, double rot) {
        double[] z = Astro.vec(ra * Astro.DEG, dec * Astro.DEG);
        double[] pole = {0, 0, 1};
        double[] east = cross(pole, z);
        double ne = Math.sqrt(dot(east, east));
        if (ne < 1e-9) east = new double[]{0, 1, 0};
        else for (int i = 0; i < 3; i++) east[i] /= ne;
        double[] north = cross(z, east);
        double c = Math.cos(rot * Astro.DEG), s = Math.sin(rot * Astro.DEG);
        // picture right = west turned, picture down = south turned
        double[] x = new double[3], y = new double[3];
        for (int i = 0; i < 3; i++) {
            double wx = -east[i], sy = -north[i];
            x[i] = c * wx + s * sy;
            y[i] = -s * wx + c * sy;
        }
        SkySolver.Solution sol = new SkySolver.Solution();
        sol.R = new double[]{x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
        sol.model = m;
        sol.matched = 200;
        return sol;
    }

    static ReportCard card(SkySolver.Solution sol, int x0, int y0, int w, int h) {
        PostProcessor.Output o = CaptureReportTest.frames(11, 16, -1, 0);
        o.solution = sol;
        o.analysis = new Renderer.Analysis();
        o.analysis.cx0 = x0;
        o.analysis.cy0 = y0;
        o.analysis.cw = w;
        o.analysis.ch = h;
        ReportCard.Input in = new ReportCard.Input();
        in.out = o;
        in.props = new Properties();
        in.zone = TimeZone.getTimeZone("UTC");
        in.catalog = cat;
        return ReportCard.build(in);
    }

    static SkyGuide.Item item(SkyGuide g, String name) {
        for (SkyGuide.Item it : g.items) if ("constellation".equals(it.kind) && it.name.equals(name)) return it;
        return null;
    }

    static boolean same(SkyGuide a, SkyGuide b) {
        if (b == null || a.figures.size() != b.figures.size()) return false;
        for (int i = 0; i < a.figures.size(); i++) {
            SkyGuide.Line p = a.figures.get(i), q = b.figures.get(i);
            if (!p.c.equals(q.c) || p.a != q.a || p.b != q.b || p.pts.length != q.pts.length) return false;
            for (int k = 0; k < p.pts.length; k++) if (Math.abs(p.pts[k] - q.pts[k]) > 6e-4) return false;
        }
        for (int i = 0; i < a.items.size(); i++) {
            SkyGuide.Item p = a.items.get(i), q = b.items.get(i);
            if (Double.isNaN(p.lx) != Double.isNaN(q.lx)) return false;
            if (!Double.isNaN(p.lx) && (Math.abs(p.lx - q.lx) > 6e-4 || Math.abs(p.ly - q.ly) > 6e-4)) return false;
        }
        return true;
    }

    /** Whether the named star is one of the figure's. */
    static boolean near(Figures.Figure f, BrightStars cat, String star) {
        int k = -1;
        for (int i = 0; i < cat.n; i++) if (cat.name[i].equals(star)) k = i;
        if (f == null || k < 0) return false;
        for (double[][] run : f.runs) for (double[] v : run) if (Astro.sep(v, cat.vec(k)) < 0.02 * Astro.DEG) return true;
        return false;
    }

    /** A picture of the catalogue's stars with the figures and names drawn as the app draws them, to look at. */
    static void draw(File f, SkyGuide g, SkySolver.Solution sol, BrightStars cat, int x0, int y0, int w, int h) throws Exception {
        int W = 1360, H = (int) Math.round(W * h / (double) w);
        double sc = W / (double) w;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setColor(new Color(8, 10, 22));
        gr.fillRect(0, 0, W, H);
        for (int k = 0; k < cat.n; k++) {
            if (cat.vmag[k] > 5.5f) continue;
            double[] b = new double[3];
            double[] v = cat.vec(k);
            com.brandon.nightsky.processing.Rot.apply(com.brandon.nightsky.processing.Rot.transpose(sol.R), v[0], v[1], v[2], b);
            if (b[2] <= 0 || Math.hypot(b[0] / b[2], b[1] / b[2]) > 1.2) continue;
            double[] p = sol.pixel(v);
            if (p == null) continue;
            double x = (p[0] - x0) * sc, y = (p[1] - y0) * sc;
            double r = Math.max(0.6, 3.2 - 0.5 * cat.vmag[k]);
            gr.setColor(Color.WHITE);
            gr.fill(new java.awt.geom.Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
        float unit = Math.max(1f, Math.max(W, H) / 1000f);
        gr.setColor(new Color(0xA8, 0xC8, 0xFF, 0x80));
        gr.setStroke(new BasicStroke(Math.max(1f, 1.1f * unit), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float gap = 6 * unit;
        for (SkyGuide.Line l : g.figures) {
            float[] xy = trimmed(l, W, H, l.a ? gap : 0, l.b ? gap : 0);
            if (xy == null) continue;
            Path2D.Float p = new Path2D.Float();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            gr.draw(p);
        }
        gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(15 * unit * 0.95f)));
        for (SkyGuide.Item it : g.items) {
            if (!"constellation".equals(it.kind) || Double.isNaN(it.x)) continue;
            double x = (Double.isNaN(it.lx) ? it.x : it.lx) * W, y = (Double.isNaN(it.ly) ? it.y : it.ly) * H;
            String n = it.name.toUpperCase(Locale.US);
            gr.setColor(new Color(255, 255, 255, 0x90));
            gr.drawString(n, (float) (x - gr.getFontMetrics().stringWidth(n) / 2.0), (float) y);
        }
        gr.dispose();
        javax.imageio.ImageIO.write(img, "png", f);
    }

    /** TrackPainter.figurePath's cut, for the picture. */
    static float[] trimmed(SkyGuide.Line l, int w, int h, float from, float to) {
        int n = l.pts.length / 2;
        float[] x = new float[n], y = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            x[i] = l.pts[2 * i] * w;
            y[i] = l.pts[2 * i + 1] * h;
            if (i > 0) total += (float) Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
        float end = total - to;
        if (end - from < 2f) return null;
        float[] out = new float[2 * (n + 2)];
        int k = 0;
        float at = 0;
        boolean started = false;
        for (int i = 1; i < n; i++) {
            float seg = (float) Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            float a0 = at, a1 = at + seg;
            at = a1;
            if (seg <= 0f || a1 < from) continue;
            if (!started) {
                float t = (from - a0) / seg;
                out[k++] = x[i - 1] + t * (x[i] - x[i - 1]);
                out[k++] = y[i - 1] + t * (y[i] - y[i - 1]);
                started = true;
            }
            if (a1 >= end) {
                float t = (end - a0) / seg;
                out[k++] = x[i - 1] + t * (x[i] - x[i - 1]);
                out[k++] = y[i - 1] + t * (y[i] - y[i - 1]);
                break;
            }
            out[k++] = x[i];
            out[k++] = y[i];
        }
        if (k < 4) return null;
        float[] r = new float[k];
        System.arraycopy(out, 0, r, 0, k);
        return r;
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }
}
