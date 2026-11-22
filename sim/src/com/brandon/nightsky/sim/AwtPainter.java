package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Painter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** The app's {@link Painter} drawn with AWT, for the tests' pictures of the close-ups' cards and the replay's captions (2.4). */
final class AwtPainter implements Painter {
    final BufferedImage img;
    private final Graphics2D g;

    AwtPainter(int[] argb, int w, int h) {
        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    int[] pixels() {
        g.dispose();
        int w = img.getWidth(), h = img.getHeight();
        return img.getRGB(0, 0, w, h, null, 0, w);
    }

    private Font font(float size, boolean bold) {
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 12).deriveFont(size);
    }

    @Override
    public float width(String s, float size, boolean bold) {
        return (float) g.getFontMetrics(font(size, bold)).getStringBounds(s, g).getWidth();
    }

    @Override
    public void text(String s, float x, float y, float size, boolean bold, int argb) {
        g.setFont(font(size, bold));
        g.setColor(new Color(0, 0, 0, 150));
        float d = Math.max(1f, size / 18f);
        g.drawString(s, x + d, y + d);
        g.setColor(new Color(argb, true));
        g.drawString(s, x, y);
    }

    @Override
    public void gradient(float x0, float y0, float x1, float y1, int top, int bottom) {
        g.setPaint(new GradientPaint(x0, y0, new Color(top, true), x0, y1, new Color(bottom, true)));
        g.fill(new Rectangle2D.Float(x0, y0, x1 - x0, y1 - y0));
    }

    @Override
    public void ring(float cx, float cy, float r, float stroke, int argb) {
        g.setStroke(new BasicStroke(stroke));
        g.setColor(new Color(argb, true));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
    }

    @Override
    public void disc(float cx, float cy, float r, int argb) {
        g.setColor(new Color(argb, true));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
    }

    @Override
    public void line(float x0, float y0, float x1, float y1, float stroke, int argb) {
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(argb, true));
        g.draw(new Line2D.Float(x0, y0, x1, y1));
    }
}
