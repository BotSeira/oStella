package xyz.zcraft.ostella.service;

import xyz.zcraft.osu.parser.ReplayAnalyzer;
import xyz.zcraft.osu.parser.data.replay.HitEvent;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Whole-slider inspection: geometry, cursor travel, spatial error and node outcomes. */
final class SliderBreakRenderer {
    private static final Color INK = new Color(35, 42, 52), MUTED = new Color(99, 110, 125);
    private static final Color RED = new Color(202, 53, 62);
    private static final Color BLUE = new Color(40, 105, 177), GRID = new Color(222, 228, 235);
    private static final int WIDTH = 1200;

    record Sample(double time, double x, double y, int keys) {}

    static final class Model {
        final MissVisualizeService.MissVisualizationData data;
        final double start, end, radius, followRadius;
        final ReplayAnalyzer.SliderPath path;
        final List<HitEvent> ticks;
        final List<Sample> samples;

        Model(MissVisualizeService.MissVisualizationData data) {
            this.data = data;
            var slider = data.target().hitObject();
            start = slider.getTime();
            end = start + ReplayAnalyzer.sliderDuration(data.beatmap(), slider);
            radius = data.difficulty().getCircleRadiusInPixel();
            followRadius = radius * 2.4 + Math.clamp(37 - radius, 0, 5);
            double ar = data.difficulty().ar();
            double preempt = (ar < 5 ? 1800 - ar * 120 : 1200 - (ar - 5) * 150) * data.difficulty().clockRate();
            double mapAr = preempt < 1200 ? 5 + (1200 - preempt) / 150 : (1800 - preempt) / 120;
            int[] stacks = ReplayAnalyzer.calculateStackHeights(data.beatmap(), mapAr);
            double stackUnit = (1 - .7 * (data.difficulty().cs() - 5) / 5) / 2 * 6.4;
            path = new ReplayAnalyzer.SliderPath(slider, stacks[data.target().objectIndex()] * stackUnit, data.hardRock());
            ticks = data.nearbyHitEvents().stream().filter(e -> e.eventType() == HitEvent.EventType.SLIDER_TICK)
                    .sorted(Comparator.comparingLong(HitEvent::analysisTime)).toList();
            var frames = data.keyFrames().stream().filter(f -> Float.isFinite(f.cursorX()) && Float.isFinite(f.cursorY()))
                    .sorted(Comparator.comparingLong(OsuReplay.TimedKeyFrame::time)).toList();
            if (frames.isEmpty()) throw new IllegalArgumentException("Replay contains no finite cursor samples");
            // Include the moving ball between replay frames, especially across repeats.
            // Connecting only recorded cursor samples would hide the ball's return trip.
            var times = new java.util.TreeSet<Double>();
            times.add(start);
            times.add(end);
            for (var f : frames) if (f.time() > start && f.time() < end) times.add((double) f.time());
            for (var tick : ticks) if (tick.analysisTime() >= start && tick.analysisTime() <= end)
                times.add((double) tick.analysisTime());
            double step = Math.max(8, (end - start) / 4096);
            for (double at = start + step; at < end; at += step) times.add(at);
            samples = times.stream().map(at -> interpolate(frames, at)).toList();
        }

        Point2D point(HitEvent event) {
            if (event.aimBias() != null) return new Point2D.Double(
                    event.cursorX() - Math.cos(event.aimBias().theta()) * event.aimBias().distance(),
                    event.cursorY() - Math.sin(event.aimBias().theta()) * event.aimBias().distance());
            return ball(event.analysisTime());
        }

        Point2D ball(double time) {
            var p = path.positionAt(ReplayAnalyzer.sliderProgress(data.target().hitObject(), end - start, time));
            return new Point2D.Double(p.x(), p.y());
        }

    }

    private static Sample interpolate(List<OsuReplay.TimedKeyFrame> frames, double time) {
        int low = 0, high = frames.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).time() <= time) low = middle + 1;
            else high = middle;
        }
        int i = Math.max(0, low - 1);
        var a = frames.get(i);
        var b = frames.get(Math.min(i + 1, frames.size() - 1));
        double t = Math.clamp((time - a.time()) / Math.max(1, b.time() - a.time()), 0, 1);
        return new Sample(time, a.cursorX() + (b.cursorX() - a.cursorX()) * t,
                a.cursorY() + (b.cursorY() - a.cursorY()) * t, a.key());
    }

    static byte[] render(MissVisualizeService.MissVisualizationData data) {
        var model = new Model(data);
        int height = 660;
        var canvas = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        var g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, height);
            text(g, "#" + data.index() + "  SLIDER BREAK", 32, 40, 26, INK);
            text(g, "Tick " + (model.ticks.indexOf(data.target()) + 1) + " / " + model.ticks.size()
                    + "  @ " + time(data.target().analysisTime()), 800, 40, 20, RED);
            clippedText(g, data.beatmap().getArtist() + " - " + data.beatmap().getTitle()
                    + " [" + data.beatmap().getVersion() + "]", 32, 72, 1136);
            geometry(g, model);
            timeline(g, model);
        } finally { g.dispose(); }
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Could not render slider break", e); }
    }

    private static void geometry(Graphics2D g, Model m) {
        var target = m.point(m.data.target());
        double minX = target.getX() - m.followRadius, maxX = target.getX() + m.followRadius;
        double minY = target.getY() - m.followRadius, maxY = target.getY() + m.followRadius;
        var curve = new ArrayList<Point2D>();
        for (int i = 0; i <= 512; i++) {
            var p = m.path.positionAt(i / 512.0);
            curve.add(new Point2D.Double(p.x(), p.y()));
            minX = Math.min(minX, p.x() - m.radius); maxX = Math.max(maxX, p.x() + m.radius);
            minY = Math.min(minY, p.y() - m.radius); maxY = Math.max(maxY, p.y() + m.radius);
        }
        for (var s : m.samples) {
            minX = Math.min(minX, s.x()); maxX = Math.max(maxX, s.x());
            minY = Math.min(minY, s.y()); maxY = Math.max(maxY, s.y());
        }
        double scale = Math.min(1060 / Math.max(1, maxX - minX), 360 / Math.max(1, maxY - minY));
        var transform = new AffineTransform();
        transform.translate(600, 300);
        transform.scale(scale, scale);
        transform.translate(-(minX + maxX) / 2, -(minY + maxY) / 2);
        var field = (Graphics2D) g.create();
        field.clipRect(32, 100, 1136, 430);
        var line = new Path2D.Double();
        for (int i = 0; i < curve.size(); i++) {
            var p = transform.transform(curve.get(i), null);
            if (i == 0) line.moveTo(p.getX(), p.getY()); else line.lineTo(p.getX(), p.getY());
        }
        field.setColor(GRID);
        field.setStroke(new BasicStroke((float) (2 * m.radius * scale + 3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        field.draw(line);
        field.setColor(new Color(246, 248, 250));
        field.setStroke(new BasicStroke((float) (2 * m.radius * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        field.draw(line);
        var t = transform.transform(target, null);
        field.setColor(new Color(202, 53, 62, 22));
        field.fill(circle(t, m.followRadius * scale));
        field.setColor(RED);
        field.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{6, 5}, 0));
        field.draw(circle(t, m.followRadius * scale));
        field.setStroke(new BasicStroke(2));
        field.draw(circle(t, m.radius * scale));
        var trail = new Path2D.Double();
        boolean previousHeld = (m.samples.getFirst().keys() & 15) != 0;
        for (int i = 1; i < m.samples.size(); i++) {
            var a = m.samples.get(i - 1); var b = m.samples.get(i);
            var p = transform.transform(new Point2D.Double(a.x(), a.y()), null);
            var q = transform.transform(new Point2D.Double(b.x(), b.y()), null);
            boolean held = (a.keys() & 15) != 0;
            if (held != previousHeld) {
                drawTrail(field, trail, previousHeld);
                trail.reset();
            }
            if (trail.getCurrentPoint() == null) trail.moveTo(p.getX(), p.getY());
            trail.lineTo(q.getX(), q.getY());
            previousHeld = held;
        }
        drawTrail(field, trail, previousHeld);
        // Coincident repeat ticks share a position; only the selected tick needs a label.
        var groups = new java.util.LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < m.ticks.size(); i++) {
            var p = m.point(m.ticks.get(i));
            String key = Math.round(p.getX()) + ":" + Math.round(p.getY());
            groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(i);
        }
        for (var indexes : groups.values()) {
            var event = m.ticks.get(indexes.getFirst());
            var p = transform.transform(m.point(event), null);
            boolean selected = indexes.stream().anyMatch(i -> m.ticks.get(i).equals(m.data.target()));
            field.setColor(selected ? RED : INK);
            field.fill(circle(p, selected ? 7 : 5));
            if (selected) text(field, "Tick " + (m.ticks.indexOf(m.data.target()) + 1),
                    (int) p.getX() + 12, (int) p.getY() - 12, 18, RED);
        }
        var cursor = transform.transform(new Point2D.Double(m.data.target().cursorX(), m.data.target().cursorY()), null);
        field.setColor(RED); field.setStroke(new BasicStroke(2));
        field.draw(new Line2D.Double(t, cursor));
        field.draw(new Line2D.Double(cursor.getX() - 7, cursor.getY() - 7, cursor.getX() + 7, cursor.getY() + 7));
        field.draw(new Line2D.Double(cursor.getX() - 7, cursor.getY() + 7, cursor.getX() + 7, cursor.getY() - 7));
        var start = transform.transform(m.ball(m.start), null);
        var end = transform.transform(m.ball(m.end), null);
        text(field, start.distance(end) < 2 ? "S / E" : "S", (int) start.getX() - 8, (int) start.getY() + 24, 16, INK);
        if (start.distance(end) >= 2) text(field, "E", (int) end.getX() - 8, (int) end.getY() + 24, 16, INK);
        field.dispose();
    }

    private static void timeline(Graphics2D g, Model m) {
        double targetDistance = m.point(m.data.target()).distance(m.data.target().cursorX(), m.data.target().cursorY());
        double maximum = Math.max(m.followRadius, Math.max(targetDistance, m.samples.stream()
                .mapToDouble(s -> m.ball(s.time()).distance(s.x(), s.y())).max().orElse(0)) * 1.1);
        text(g, "Distance (px)", 32, 537, 16, MUTED);
        text(g, String.format(Locale.ROOT, "%.0f", maximum), 48, 560, 14, MUTED);
        text(g, "0", 64, 614, 14, MUTED);
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1));
        g.drawLine(100, 610, 1112, 610);
        // Both thresholds matter: reacquiring tracking is stricter than maintaining it.
        for (double limit : new double[]{m.radius, m.followRadius}) {
            double y = 610 - 56 * limit / maximum;
            g.setColor(MUTED);
            g.setStroke(limit == m.radius ? new BasicStroke(1)
                    : new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
            g.draw(new Line2D.Double(100, y, 1112, y));
            double labelY = limit == m.radius ? y + 4
                    : Math.min(y + 4, 610 - 56 * m.radius / maximum + 4 - 16);
            g.setStroke(new BasicStroke(1));
            g.draw(new Line2D.Double(1112, y, 1118, labelY - 4));
            text(g, limit == m.radius ? "Acquire" : "Follow", 1120, (int) labelY, 13, MUTED);
        }
        var curve = new Path2D.Double();
        for (var sample : m.samples) {
            double x = 100 + 1012 * (sample.time() - m.start) / Math.max(1, m.end - m.start);
            double y = 610 - 56 * m.ball(sample.time()).distance(sample.x(), sample.y()) / maximum;
            if (curve.getCurrentPoint() == null) curve.moveTo(x, y);
            else curve.lineTo(x, y);
        }
        g.setColor(BLUE);
        g.setStroke(new BasicStroke(2));
        g.draw(curve);
        double targetX = 100 + 1012 * Math.clamp((m.data.target().analysisTime() - m.start) / Math.max(1, m.end - m.start), 0, 1);
        double targetY = 610 - 56 * targetDistance / maximum;
        g.setColor(RED);
        g.fill(new Ellipse2D.Double(targetX - 4, targetY - 4, 8, 8));
        text(g, time(m.start), 100, 636, 16, MUTED);
        text(g, time(m.end), 1020, 636, 16, MUTED);
        String status = targetStatus(m);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
//        text(g, status, 600 - g.getFontMetrics().stringWidth(status) / 2, 636, 17, INK);
    }

    static String targetStatus(Model m) {
        double distance = m.point(m.data.target()).distance(m.data.target().cursorX(), m.data.target().cursorY());
        boolean released = (m.data.target().keyFlags() & 15) == 0;
        if (distance > m.followRadius) return released ? "Out of range / key released" : "Out of range";
        if (released) return "Key released";
        return distance > m.radius ? "Outside acquire range" : "Missed tick";
    }
    private static Ellipse2D circle(Point2D p, double r) { return new Ellipse2D.Double(p.getX() - r, p.getY() - r, r * 2, r * 2); }
    private static void drawTrail(Graphics2D g, Path2D path, boolean held) {
        g.setColor(held ? BLUE : MUTED);
        g.setStroke(held ? new BasicStroke(2.5f)
                : new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{4, 4}, 0));
        g.draw(path);
    }
    private static String time(double ms) { long t = Math.round(ms); return String.format(Locale.ROOT, "%02d:%02d.%03d", t / 60000, t / 1000 % 60, t % 1000); }
    private static void text(Graphics2D g, String value, int x, int y, int size, Color color) {
        g.setColor(color); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size)); g.drawString(value, x, y);
    }
    private static void clippedText(Graphics2D g, String value, int x, int y, int width) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        while (value.length() > 1 && g.getFontMetrics().stringWidth(value) > width) value = value.substring(0, value.length() - 2) + "…";
        text(g, value, x, y, 18, MUTED);
    }
}
