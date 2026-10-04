package xyz.zcraft.ostella.snapshot;

import xyz.zcraft.osu.parser.ReplayAnalyzer;
import xyz.zcraft.osu.parser.data.beatmap.HitObject;
import xyz.zcraft.osu.parser.data.replay.HitEvent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Locale;

/**
 * A full 16:9 gameplay frame. Geometry uses 512×384, HUD uses stable's 480p units.
 */
public final class SnapshotRenderer {
    public static final int WIDTH = 1920, HEIGHT = 1080;
    private static final double SCALE = HEIGHT / 480.0;
    private static final double LEFT = (WIDTH - 512 * SCALE) / 2;
    private static final double TOP = (HEIGHT - 384 * SCALE) / 2 + 8 * SCALE;
    private static final SnapshotSkin SKIN = new SnapshotSkin();
    // WhiteCat has no cursormiddle: use the legacy disjoint trail's 60 Hz stamps and linear fade.
    private static final double CURSOR_TRAIL_INTERVAL = 1000.0 / 60;
    private static final double CURSOR_TRAIL_DURATION = 150;

    public static byte[] render(SnapshotScene scene, BufferedImage background) {
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(renderFrame(scene, background, WIDTH, HEIGHT), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Could not encode replay snapshot", e); }
    }

    public static BufferedImage renderFrame(SnapshotScene scene, BufferedImage background, int width, int height) {
        var canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var root = canvas.createGraphics();
        try {
            root.scale((double) width / WIDTH, (double) height / HEIGHT);
            quality(root);
            root.setColor(Color.BLACK);
            root.fillRect(0, 0, WIDTH, HEIGHT);
            if (background != null) {
                double scale = Math.max((double) WIDTH / background.getWidth(), (double) HEIGHT / background.getHeight());
                int w = (int) Math.ceil(background.getWidth() * scale), h = (int) Math.ceil(background.getHeight() * scale);
                root.drawImage(background, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
                root.setColor(new Color(0, 0, 0, 210));
                root.fillRect(0, 0, WIDTH, HEIGHT);
            }
            var field = (Graphics2D) root.create();
            try {
                field.translate(LEFT, TOP);
                field.scale(SCALE, SCALE);
                // Objects may legitimately spill outside the playfield. Clip only at screen bounds.
                var objects = scene.objects().stream().sorted(Comparator.comparingDouble(SnapshotScene.ObjectState::start).reversed()).toList();
                for (var object : objects) {
                    if (object.object().getObjectType() == HitObject.ObjectType.SLIDER) slider(field, scene, object);
                    else if (object.object().getObjectType() == HitObject.ObjectType.SPINNER)
                        spinner(field, scene, object);
                    else circle(field, scene, object, object.x(), object.y(), object.alpha(), object.scale());
                }
                // Approach circles have their own foreground layer, matching stable skins.
                for (var object : objects)
                    if (object.object().getObjectType() != HitObject.ObjectType.SPINNER) {
                        SKIN.draw(field, "approachcircle", object.x(), object.y(), scene.radius() / 64 * object.approachScale(),
                                object.approachAlpha(), SKIN.combo(object.colour()), 0);
                    }
                for (var judgement : scene.judgements()) judgement(field, scene, judgement);
                cursor(field, scene);
            } finally {
                field.dispose();
            }
            hud(root, scene);
        } finally {
            root.dispose();
        }
        return canvas;
    }

    private static void circle(Graphics2D g, SnapshotScene scene, SnapshotScene.ObjectState object,
                               double x, double y, double alpha, double animationScale) {
        if (!object.headVisible()) return;
        double scale = scene.radius() / 64 * animationScale;
        SKIN.draw(g, "hitcircle", x, y, scale, alpha, SKIN.combo(object.colour()), 0);
        SKIN.draw(g, "hitcircleoverlay", x, y, scale, alpha, null, 0);
        SKIN.number(g, Integer.toString(object.number()), "default", SKIN.integer("HitCircleOverlap", 0), x, y,
                scale, alpha);
    }

    private static void slider(Graphics2D graphics, SnapshotScene scene, SnapshotScene.ObjectState object) {
        var g = (Graphics2D) graphics.create();
        try {
            double radius = scene.radius();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) object.bodyAlpha()));
            Path2D path = path(object.path(), object.snakeStart(), object.snakeEnd(), object.object().getLength());
            g.setStroke(new BasicStroke((float) (radius * 2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(SKIN.colour("SliderBorder", Color.WHITE));
            g.draw(path);
            // Soft inner bevel plus the skin's overridden track colour.
            g.setStroke(new BasicStroke((float) (radius * 2 - Math.max(2, radius * .13)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(32, 32, 32));
            g.draw(path);
            g.setStroke(new BasicStroke((float) (radius * 2 - Math.max(4, radius * .23)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(SKIN.colour("SliderTrackOverride", SKIN.combo(object.colour()).darker()));
            g.draw(path);
            double duration = object.end() - object.start(), spanDuration = duration / Math.max(1, object.object().getSlides());
            int span = spanDuration <= 0 ? 0 : Math.min(object.object().getSlides() - 1,
                    Math.max(0, (int) ((scene.time() - object.start()) / spanDuration)));
            double spanEnd = object.start() + (span + 1) * spanDuration;
            // Uncollected score ticks in the current span; repeats use reverse arrows.
            for (var event : scene.analyze().events()) {
                if (event.objectIndex() != object.index() || event.eventType() != HitEvent.EventType.SLIDER_TICK
                        || event.eventTime() <= scene.time() || event.eventTime() >= spanEnd - 1) continue;
                double progress = ReplayAnalyzer.sliderProgress(object.object(), object.end() - object.start(), event.eventTime());
                if (progress < object.snakeStart() || progress > object.snakeEnd()) continue;
                var point = object.path().positionAt(progress);
                SKIN.draw(g, "sliderscorepoint", point.x(), point.y(), radius / 32, object.bodyAlpha(), null, 0);
            }
            if (span < object.object().getSlides() - 1 && scene.time() < object.end()) {
                boolean reverse = (span & 1) != 0;
                var point = object.path().positionAt(reverse ? 0 : 1);
                var tangent = object.path().positionAt(reverse ? .01 : .99);
                double angle = Math.atan2(tangent.y() - point.y(), tangent.x() - point.x());
                double pulse = 1 + .08 * Math.sin((scene.time() - object.start()) / 80);
                SKIN.draw(g, "reversearrow", point.x(), point.y(), radius / 64 * pulse, object.bodyAlpha(), null, angle);
            }
            // A deliberately transparent sliderendcircle in WhiteCat stays transparent.
            var end = object.path().positionAt(1);
            SKIN.draw(g, "sliderendcircle", end.x(), end.y(), radius / 64, object.bodyAlpha(), SKIN.combo(object.colour()), 0);
        } finally {
            g.dispose();
        }
        circle(graphics, scene, object, object.x(), object.y(), object.alpha(), object.scale());
        if (scene.time() >= object.start() && scene.time() < object.end()) {
            var ball = object.path().positionAt(object.progress());
            var tangent = object.path().positionAt(Math.clamp(object.progress() + .001, 0, 1));
            double angle = Math.atan2(tangent.y() - ball.y(), tangent.x() - ball.x());
            if (object.following()) SKIN.draw(graphics, "sliderfollowcircle", ball.x(), ball.y(), scene.radius() / 64,
                    1, null, 0);
            SKIN.draw(graphics, "sliderb", ball.x(), ball.y(), scene.radius() / 64, 1,
                    SKIN.integer("AllowSliderBallTint", 0) == 1 ? SKIN.combo(object.colour()) : null, angle);
        }
    }

    private static Path2D path(ReplayAnalyzer.SliderPath path, double start, double end, double length) {
        var line = new Path2D.Double();
        var first = path.positionAt(start);
        line.moveTo(first.x(), first.y());
        int segments = Math.clamp((int) Math.ceil(Math.max(1, length * Math.max(0, end - start)) / 1.5), 2, 2500);
        for (int i = 1; i <= segments; i++) {
            var point = path.positionAt(start + (end - start) * i / segments);
            line.lineTo(point.x(), point.y());
        }
        return line;
    }

    private static void spinner(Graphics2D g, SnapshotScene scene, SnapshotScene.ObjectState object) {
        double alpha = object.bodyAlpha();
        SKIN.draw(g, "spinner-background", 256, 192, .8, alpha, null, 0);
        double angle = 0, previous = Double.NaN;
        double rotations = 0;
        for (var frame : scene.analyze().replay().timedKeyFrames()) {
            if (frame.time() < object.start() || frame.time() > Math.min(scene.time(), object.end())) continue;
            double current = Math.atan2(frame.cursorY() - 192, frame.cursorX() - 256);
            if (Double.isFinite(previous) && (frame.key() & 15) != 0) {
                double delta = Math.atan2(Math.sin(current - previous), Math.cos(current - previous));
                angle += delta;
                rotations += Math.abs(delta) / (2 * Math.PI);
            }
            previous = current;
        }
        SKIN.draw(g, "spinner-circle", 256, 192, .8, alpha, null, angle);
        SKIN.draw(g, "spinner-middle", 256, 192, .8, alpha, null, 0);
        SKIN.draw(g, "spinner-top", 256, 192, .8, alpha, null, 0);
        double remaining = SnapshotScene.clamp((object.end() - scene.time()) / Math.max(1, object.end() - object.start()));
        SKIN.draw(g, "spinner-approachcircle", 256, 192, .8 * remaining, alpha, null, 0);
        if (scene.time() < object.start()) SKIN.draw(g, "spinner-spin", 256, 285, .8, alpha, null, 0);
        else if (scene.time() < object.end()) {
            double seconds = (scene.time() - object.start()) / (1000 * scene.clockRate());
            SKIN.draw(g, "spinner-rpm", 256, 325, .8, alpha, null, 0);
            text(g, Long.toString(Math.round(rotations * 60 / Math.max(.1, seconds))), 283, 348, 18, true, Color.WHITE);
            long spinEvents = scene.analyze().events().stream().filter(e -> e.objectIndex() == object.index()
                    && e.eventType() == HitEvent.EventType.SPINNER_SPIN).count();
            long collected = scene.analyze().events().stream().filter(e -> e.objectIndex() == object.index()
                    && e.eventType() == HitEvent.EventType.SPINNER_SPIN && e.wasHit() && scene.judgedAt(e) <= scene.time()).count();
            if (spinEvents > 0 && collected >= Math.max(0, spinEvents - 2))
                SKIN.draw(g, "spinner-clear", 256, 270, .8, alpha, null, 0);
        }
    }

    private static void judgement(Graphics2D g, SnapshotScene scene, SnapshotScene.Judgement judgement) {
        double age = scene.time() - judgement.time();
        if (age < 0 || age >= 1000 * scene.clockRate()) return;
        String name = switch (judgement.result()) {
            case PERFECT -> "hit300";
            case OK -> "hit100";
            case MEH -> "hit50";
            case MISS -> "hit0";
        };
        double alpha = SnapshotScene.clamp((1000 * scene.clockRate() - age) / (250 * scene.clockRate()));
        double scale = .8 * (1 + .2 * Math.exp(-age / (80 * scene.clockRate())));
        double y = judgement.y() + (judgement.result() == HitEvent.HitResult.MISS ? Math.pow(age / (1000 * scene.clockRate()), 2) * 25 : 0);
        SKIN.drawJudgement(g, name, judgement.x(), y, scale, alpha);
    }

    private static void cursor(Graphics2D g, SnapshotScene scene) {
        double duration = CURSOR_TRAIL_DURATION * scene.clockRate();
        double interval = CURSOR_TRAIL_INTERVAL * scene.clockRate();
        double start = Math.ceil(Math.max(0, scene.time() - duration) / interval) * interval;
        for (double t = start; t < scene.time(); t += interval) {
            var point = scene.cursorAt(t);
            double alpha = SnapshotScene.clamp(1 - (scene.time() - t) / duration);
            SKIN.draw(g, "cursortrail", point.x(), point.y(), .5, alpha, null, 0);
        }
        SKIN.draw(g, "cursor", scene.cursor().x(), scene.cursor().y(), .5, 1, null, 0);
    }

    private static void hud(Graphics2D root, SnapshotScene scene) {
        var g = (Graphics2D) root.create();
        try {
            g.scale(SCALE, SCALE);
            double w = WIDTH / SCALE, h = HEIGHT / SCALE;
            g.setColor(new Color(255, 255, 255, 40));
            g.fill(new Rectangle2D.Double(0, 0, w, 3));
            g.setColor(new Color(238, 220, 210));
            g.fill(new Rectangle2D.Double(0, 0, w * scene.progress(), 3));
            text(g, String.format(Locale.ROOT, "%.2f pp", scene.pp()), w - 22, 34, 26, true, Color.WHITE);
            text(g, String.format(Locale.ROOT, "%.2f%%", scene.accuracy() * 100), w - 22, 58, 19, true, Color.WHITE);
            text(g, String.format(Locale.ROOT, "%d:%02d.%03d", scene.time() / 60000, scene.time() / 1000 % 60, scene.time() % 1000),
                    20, 28, 15, false, new Color(225, 225, 225));
            text(g, "Playing: " + scene.analyze().replay().playerName(), 20, 49, 13, false, new Color(200, 200, 200));
            SKIN.number(g, Integer.toString(scene.performance().currentCombo), SKIN.setting("ComboPrefix", "score"),
                    SKIN.integer("ComboOverlap", 0), 25 + Integer.toString(scene.performance().currentCombo).length() * 7,
                    h - 27, 1.2, 1);
            text(g, "x", 37 + Integer.toString(scene.performance().currentCombo).length() * 14, h - 22, 22, false, Color.WHITE);
            timingBar(g, scene, w / 2, h - 23);
            int[] counts = scene.keyCounts();
            int keys = SnapshotScene.overlayKeys(scene.cursor().keys());
            for (int i = 0; i < 4; i++) {
                double x = w - 45, y = h / 2 - 60 + i * 35;
                boolean pressed = (keys & (1 << i)) != 0;
                g.setColor(pressed ? new Color(237, 221, 213) : new Color(255, 255, 255, 55));
                g.fill(new RoundRectangle2D.Double(x - 13, y - 13, 27, 27, 3, 3));
                text(g, new String[]{"K1", "K2", "M1", "M2"}[i], x - 7, y + 4, 11, false, pressed ? Color.BLACK : Color.WHITE);
                SKIN.draw(g, "inputoverlay-key", x + 15, y, .6, pressed ? 1 : .35, null, 0);
                text(g, Integer.toString(counts[i]), x - 18, y + 4, 10, true, new Color(210, 210, 210));
            }
            String stats = String.format(Locale.ROOT, "300  %d     100  %d     50  %d     Miss  %d",
                    scene.performance().n300, scene.performance().n100, scene.performance().n50, scene.performance().misses);
            text(g, stats, w - 20, 81, 11, true, new Color(195, 195, 195));
            int mods = ReplayAnalyzer.effectiveLegacyMods(scene.analyze().replay());
            String modString = ((mods & 8) != 0 ? "HD " : "") + ((mods & 16) != 0 ? "HR " : "")
                    + ((mods & 64) != 0 ? "DT " : "") + ((mods & 512) != 0 ? "NC " : "")
                    + ((mods & 256) != 0 ? "HT " : "") + ((mods & 2) != 0 ? "EZ " : "");
            text(g, modString.trim(), w - 22, 101, 12, true, new Color(215, 205, 195));
        } finally {
            g.dispose();
        }
    }

    private static void timingBar(Graphics2D g, SnapshotScene scene, double x, double y) {
        var diff = scene.analyze().calculatedDifficulty();
        double meh = diff.getMehWindow(), ok = diff.getOkWindow(), perfect = diff.getPerfectWindow();
        double width = 170;
        g.setColor(new Color(239, 186, 54));
        g.fill(new Rectangle2D.Double(x - width / 2, y - 2, width, 4));
        g.setColor(new Color(99, 192, 66));
        g.fill(new Rectangle2D.Double(x - width / 2 * ok / meh, y - 2, width * ok / meh, 4));
        g.setColor(new Color(85, 187, 240));
        g.fill(new Rectangle2D.Double(x - width / 2 * perfect / meh, y - 2, width * perfect / meh, 4));
        double sum = 0, squares = 0;
        int count = scene.errors().size();
        for (var error : scene.errors()) {
            double age = (scene.time() - error[0]) / scene.clockRate();
            double alpha = SnapshotScene.clamp(1 - age / 10000);
            g.setColor(new Color(255, 255, 255, (int) (alpha * 220)));
            double position = x + Math.clamp(error[1] / meh, -1, 1) * width / 2;
            g.draw(new Line2D.Double(position, y - 9, position, y + 4));
            sum += error[1];
            squares += error[1] * error[1];
        }
        g.setColor(Color.WHITE);
        g.draw(new Line2D.Double(x, y - 5, x, y + 7));
        if (count > 0) {
            double average = sum / count;
            double ur = Math.sqrt(Math.max(0, squares / count - average * average)) * 10;
            text(g, String.format(Locale.ROOT, "UR %.2f", ur), x, y + 16, 10, true, new Color(170, 170, 170));
        }
    }

    private static void text(Graphics2D g, String text, double x, double y, int size, boolean right, Color colour) {
        g.setFont(new Font("Segoe UI", Font.PLAIN, size));
        double width = g.getFontMetrics().stringWidth(text);
        if (right) x -= width;
        g.setColor(new Color(0, 0, 0, 160));
        g.drawString(text, (float) x + 1, (float) y + 1);
        g.setColor(colour);
        g.drawString(text, (float) x, (float) y);
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
