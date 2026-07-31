package xyz.zcraft.ostella.service;

import xyz.zcraft.ostella.exception.ApiException;
import xyz.zcraft.ostella.network.ErrorCode;
import xyz.zcraft.osu.parser.data.beatmap.DifficultyAttribute;
import xyz.zcraft.osu.parser.data.beatmap.HitObject;
import xyz.zcraft.osu.parser.data.beatmap.OsuBeatmap;
import xyz.zcraft.osu.parser.data.replay.HitEvent;
import xyz.zcraft.osu.parser.data.replay.OsuReplay;
import xyz.zcraft.osu.parser.data.replay.ReplayAnalyze;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class MissVisualizeService {
    public static final double TIMING_INDICATOR_PERCENTAGE = 2;

    private static final int CANVAS_WIDTH = 512;
    private static final int CANVAS_HEIGHT = 384;
    private static final double ZOOM_FACTOR = 1.3;
    private static final int WINDOW_MILLIS = 250;

    private static final List<Color> PATH_COLORS;

    static {
        PATH_COLORS = List.of(
                Colors.UNPRESSED_COLOR, Colors.COLOR_MISS, Colors.COLOR_MEH, Colors.COLOR_OK,
                Colors.COLOR_PERFECT,
                Colors.COLOR_OK, Colors.COLOR_MEH, Colors.COLOR_MISS, Colors.UNPRESSED_COLOR
        );
    }

    public static byte[] visualizeMiss(ReplayAnalyze replayAnalyze, int missIndex) {
        final List<HitEvent> missEvents = replayAnalyze.events().stream()
                .filter(hitEvent -> !hitEvent.wasHit())
                .filter(hitEvent -> hitEvent.hitObject().getObjectType() != HitObject.ObjectType.SPINNER)
                .filter(e -> e.eventType() == HitEvent.EventType.HIT_CIRCLE || e.eventType() == HitEvent.EventType.SLIDER_HEAD)
                .toList();

        if (missIndex <= 0 || missIndex > missEvents.size()) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid miss index: " + missIndex + ", should be 1-" + missEvents.size());
        }

        final HitEvent targetMiss = missEvents.get(missIndex - 1);

        final var keyFrames = replayAnalyze.replay().timedKeyFrames();

        return ImageHelper.drawMiss(
                missIndex,
                targetMiss,
                extractNearbyKeyFrames(keyFrames, targetMiss.hitObject()),
                replayAnalyze.beatmap(),
                replayAnalyze.calculatedDifficulty()
        );
    }

    private static List<OsuReplay.TimedKeyFrame> extractNearbyKeyFrames(List<OsuReplay.TimedKeyFrame> keyFrames, HitObject hitObject) {
        int leftIndex = -1, rightIndex = -1;
        for (int i = 0; i < keyFrames.size(); i++) {
            if (keyFrames.get(i).time() > hitObject.getTime()) {
                leftIndex = i;
                rightIndex = i + 1;
                break;
            }
        }

        if (leftIndex == -1) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Could not find keyframe to lookup");
        }

        while (leftIndex > 0 && keyFrames.get(leftIndex).time() >= hitObject.getTime() - WINDOW_MILLIS) {
            leftIndex--;
        }

        while (rightIndex < keyFrames.size() - 1 && keyFrames.get(rightIndex).time() <= hitObject.getTime() + WINDOW_MILLIS) {
            rightIndex++;
        }

        return keyFrames.subList(leftIndex, rightIndex + 1);
    }

    private static final class Colors {
        private static final Color PRESSED_COLOR = new Color(255, 204, 34);
        private static final Color UNPRESSED_COLOR = new Color(68, 68, 68);
        private static final Color COLOR_PERFECT = new Color(102, 204, 255);
        private static final Color COLOR_OK = new Color(136, 179, 0);
        private static final Color COLOR_MEH = new Color(255, 204, 34);
        private static final Color COLOR_MISS = new Color(239, 83, 80);
    }

    private static class ImageHelper {
        public static BufferedImage zoomAndCrop(BufferedImage originalImage, double zoomFactor) {
            if (zoomFactor == 1) return originalImage;

            int origWidth = originalImage.getWidth();
            int origHeight = originalImage.getHeight();

            int cropWidth = (int) (origWidth / zoomFactor);
            int cropHeight = (int) (origHeight / zoomFactor);

            int cropX = (origWidth - cropWidth) / 2;
            int cropY = (origHeight - cropHeight) / 2;

            BufferedImage croppedImage = originalImage.getSubimage(cropX, cropY, cropWidth, cropHeight);

            BufferedImage zoomedImage = new BufferedImage(origWidth, origHeight, originalImage.getType());
            Graphics2D g2d = zoomedImage.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.drawImage(croppedImage, 0, 0, origWidth, origHeight, null);

            g2d.dispose();
            return zoomedImage;
        }

        public static void drawSemicircle(Graphics2D g2d, boolean left, boolean fill, double centerX, double centerY, double radius, Color color) {
            double topLeftX = centerX - radius;
            double topLeftY = centerY - radius;
            double diameter = 2 * radius;

            double startAngle = left ? 90 : 270;
            double extentAngle = 180;

            Arc2D.Double semicircle = new Arc2D.Double(
                    topLeftX, topLeftY,
                    diameter, diameter,
                    startAngle, extentAngle,
                    fill ? Arc2D.PIE : Arc2D.OPEN
            );

            g2d.setColor(color);
            if (fill) {
                g2d.fill(semicircle);
            } else {
                g2d.draw(semicircle);
            }
        }

        private static int getHitWindowCategory(long offset, DifficultyAttribute diff) {
            long absOffset = Math.abs(offset);

            if (absOffset < diff.getPerfectWindow()) return 4;

            boolean isEarly = offset < 0;
            if (absOffset < diff.getOkWindow()) return isEarly ? 3 : 5;
            if (absOffset < diff.getMehWindow()) return isEarly ? 2 : 6;
            if (absOffset < diff.getMissWindow()) return isEarly ? 1 : 7;

            return isEarly ? 0 : 8;
        }

        private static byte[] drawMiss(int missIndex,
                                       HitEvent targetMiss,
                                       List<OsuReplay.TimedKeyFrame> keyFrames,
                                       OsuBeatmap beatmap,
                                       DifficultyAttribute diff) {
            final HitObject hitObject = targetMiss.hitObject();
            final double circleRadius = diff.getCircleRadiusInPixel();

            final LinkedList<Long> hitTimes = new LinkedList<>();

            BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);

            Graphics2D g2d = canvas.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

            drawNearbyObjects(hitObject, beatmap, circleRadius, g2d);

            drawTargetObject(hitObject, circleRadius, g2d);

            drawCursorPath(hitObject, keyFrames, diff, g2d);

            drawFramePoints(hitObject, keyFrames, g2d, hitTimes);

            drawText(missIndex, targetMiss, beatmap, g2d);

            drawTimingIndicator(diff, g2d, hitTimes);

            g2d.dispose();

            final ByteArrayOutputStream output = new ByteArrayOutputStream();

            try {
                ImageIO.write(zoomAndCrop(canvas, 1), "png", output);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return output.toByteArray();
        }

        private static void drawNearbyObjects(HitObject hitObject, OsuBeatmap beatmap, double circleRadius, Graphics2D g2d) {
            beatmap.getHitObjects().stream()
                    .filter(obj -> obj.getObjectType() != HitObject.ObjectType.SPINNER)
                    .filter(obj -> obj.getTime() <= hitObject.getTime() + WINDOW_MILLIS
                            && obj.getTime() >= hitObject.getTime() - WINDOW_MILLIS)
                    .forEach(obj -> {
                        if (obj.getObjectType() == HitObject.ObjectType.SLIDER) {
                            drawSlider(obj, hitObject, circleRadius, g2d);
                        }

                        Ellipse2D circle = new Ellipse2D.Double(
                                (obj.getX() - hitObject.getX() - circleRadius) * ZOOM_FACTOR + CANVAS_WIDTH * 0.5,
                                (obj.getY() - hitObject.getY() - circleRadius) * ZOOM_FACTOR + CANVAS_HEIGHT * 0.5,
                                circleRadius * 2 * ZOOM_FACTOR,
                                circleRadius * 2 * ZOOM_FACTOR
                        );

                        g2d.setColor(new Color(0, 0, 0, 50));
                        g2d.setStroke(new BasicStroke(1));
                        g2d.draw(circle);
                    });
        }

        private static void drawTimingIndicator(DifficultyAttribute diff, Graphics2D g2d, LinkedList<Long> hitTimes) {
            final double startY = (CANVAS_HEIGHT * (1 - TIMING_INDICATOR_PERCENTAGE)) / 2;
            final double barHeight = CANVAS_HEIGHT - 2 * startY;
            g2d.setStroke(new BasicStroke(4));
            g2d.setColor(Colors.COLOR_MISS);
            drawJudgeLine(diff.getMissWindow(), diff.getMissWindow(), g2d);

            g2d.setColor(Colors.COLOR_MEH);
            drawJudgeLine(diff.getMehWindow(), diff.getMissWindow(), g2d);

            g2d.setColor(Colors.COLOR_OK);
            drawJudgeLine(diff.getOkWindow(), diff.getMissWindow(), g2d);

            g2d.setColor(Colors.COLOR_PERFECT);
            drawJudgeLine(diff.getPerfectWindow(), diff.getMissWindow(), g2d);

            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            g2d.draw(new Line2D.Double(CANVAS_WIDTH - 20, startY + barHeight * 0.5, CANVAS_WIDTH, startY + barHeight * 0.5));

            g2d.setStroke(new BasicStroke(2));
            for (Long hitTime : hitTimes) {
                final double lineY = startY + (1 - (double) hitTime / diff.getMissWindow()) * barHeight * 0.5;
                g2d.setColor(PATH_COLORS.get(getHitWindowCategory(hitTime, diff)));
                g2d.draw(new Line2D.Double(CANVAS_WIDTH - 18, lineY, CANVAS_WIDTH - 2, lineY));
            }
        }

        private static void drawJudgeLine(double window, double missWindow, Graphics2D g2d) {
            final double startY = (CANVAS_HEIGHT * (1 - TIMING_INDICATOR_PERCENTAGE)) / 2;
            final double endY = CANVAS_HEIGHT - startY;
            final double barHeight = CANVAS_HEIGHT - 2 * startY;
            g2d.draw(new Line2D.Double(
                    CANVAS_WIDTH - 10,
                    startY + (1 - window / missWindow) * barHeight * 0.5,
                    CANVAS_WIDTH - 10,
                    endY - (1 - window / missWindow) * barHeight * 0.5
            ));
        }

        private static void drawText(int missIndex, HitEvent targetMiss, OsuBeatmap beatmap, Graphics2D g2d) {
            g2d.setColor(Color.BLACK);

            final Duration duration = Duration.of(targetMiss.hitObject().getTime(), ChronoUnit.MILLIS);
            String missInfo = "#" + missIndex + " Miss: " + targetMiss.eventType() + " @" +
                    String.format("%02d:%02d.%03d", duration.toMinutesPart(), duration.toSecondsPart(), duration.toMillisPart());

            g2d.setFont(new Font("Dejavu Sans", Font.PLAIN, 20));
            g2d.drawString(missInfo, 5, CANVAS_HEIGHT - 8);

            g2d.setFont(new Font("Dejavu Sans", Font.BOLD, 20));
            g2d.drawString(beatmap.getBeatmapId() + " - " + beatmap.getTitle(), 5, 20);
            g2d.setFont(new Font("Dejavu Sans", Font.PLAIN, 20));
            g2d.drawString(beatmap.getArtist() + " [" + beatmap.getVersion() + "]", 5, 40);
        }

        private static void drawFramePoints(HitObject hitObject,
                                            List<OsuReplay.TimedKeyFrame> keyFrames,
                                            Graphics2D g2d,
                                            List<Long> hitTimes) {
            int previousFlags = keyFrames.getFirst().key();

            for (var keyFrame : keyFrames) {
                final double x = (keyFrame.cursorX() - hitObject.getX()) * ZOOM_FACTOR + CANVAS_WIDTH * 0.5;
                final double y = (keyFrame.cursorY() - hitObject.getY()) * ZOOM_FACTOR + CANVAS_HEIGHT * 0.5;

                int currentFlags = keyFrame.key();
                int newlyPressed = currentFlags & ~previousFlags;
                boolean isNewPress = (newlyPressed & 15) > 0;

                boolean leftPressed = (currentFlags & 4) > 0 || (currentFlags & 1) > 0;
                boolean rightPressed = (currentFlags & 8) > 0 || (currentFlags & 2) > 0;

                if (isNewPress) {
                    g2d.setStroke(new BasicStroke(3));
                    drawSemicircle(g2d, true, false, x, y, 6 * ZOOM_FACTOR, leftPressed ? Colors.PRESSED_COLOR : Colors.UNPRESSED_COLOR);
                    drawSemicircle(g2d, false, false, x, y, 6 * ZOOM_FACTOR, rightPressed ? Colors.PRESSED_COLOR : Colors.UNPRESSED_COLOR);
                    g2d.setStroke(new BasicStroke(1));
                    hitTimes.add(keyFrame.time() - hitObject.getTime());
                }

                if (leftPressed || rightPressed) {
                    drawSemicircle(g2d, true, true, x, y, 2 * ZOOM_FACTOR, leftPressed ? Colors.PRESSED_COLOR : Colors.UNPRESSED_COLOR);
                    drawSemicircle(g2d, false, true, x, y, 2 * ZOOM_FACTOR, rightPressed ? Colors.PRESSED_COLOR : Colors.UNPRESSED_COLOR);
                } else {
                    drawSemicircle(g2d, true, true, x, y, 1 * ZOOM_FACTOR, Colors.UNPRESSED_COLOR);
                    drawSemicircle(g2d, false, true, x, y, 1 * ZOOM_FACTOR, Colors.UNPRESSED_COLOR);
                }

                previousFlags = currentFlags;
            }
        }

        private static void drawCursorPath(HitObject hitObject,
                                           List<OsuReplay.TimedKeyFrame> keyFrames,
                                           DifficultyAttribute diff,
                                           Graphics2D g2d) {
            Path2D.Double currentPath = new Path2D.Double();
            int currentCategory = -1;

            double lastX = 0;
            double lastY = 0;
            boolean hasLast = false;

            int segmentCounter = 0;

            for (var keyFrame : keyFrames) {
                long offset = keyFrame.time() - hitObject.getTime();
                int category = getHitWindowCategory(offset, diff);

                double x = (keyFrame.cursorX() - hitObject.getX()) * ZOOM_FACTOR + CANVAS_WIDTH * 0.5;
                double y = (keyFrame.cursorY() - hitObject.getY()) * ZOOM_FACTOR + CANVAS_HEIGHT * 0.5;

                if (category != currentCategory) {
                    if (currentCategory != -1) {
                        g2d.setColor(PATH_COLORS.get(currentCategory));
                        g2d.setStroke(new BasicStroke(1.5F));
                        g2d.draw(currentPath);
                    }

                    currentPath = new Path2D.Double();
                    currentCategory = category;

                    if (hasLast) {
                        currentPath.moveTo(lastX, lastY);
                        currentPath.lineTo(x, y);

                        segmentCounter = drawArrow(g2d, lastX, lastY, segmentCounter, category, x, y);
                    } else {
                        currentPath.moveTo(x, y);
                    }
                } else {
                    currentPath.lineTo(x, y);

                    if (hasLast) {
                        segmentCounter = drawArrow(g2d, lastX, lastY, segmentCounter, category, x, y);
                    }
                }

                lastX = x;
                lastY = y;
                hasLast = true;
            }

            if (currentCategory != -1) {
                g2d.setColor(PATH_COLORS.get(currentCategory));
                g2d.draw(currentPath);
            }
        }

        private static int drawArrow(Graphics2D g2d, double lastX, double lastY, int segmentCounter, int category, double x, double y) {
            double dx = x - lastX;
            double dy = y - lastY;
            double segLen = Math.hypot(dx, dy);
            if (segLen >= 6.0) {
                segmentCounter++;
                if (segmentCounter % 3 == 0) {
                    drawArrow(g2d, lastX, lastY, x, y, PATH_COLORS.get(category));
                }
            }
            return segmentCounter;
        }

        private static void drawArrow(Graphics2D g2d, double x1, double y1, double x2, double y2, Color color) {
            double placeT = 0.75;
            double px = x1 + (x2 - x1) * placeT;
            double py = y1 + (y2 - y1) * placeT;

            double angle = Math.atan2(y2 - y1, x2 - x1);

            double phi = Math.toRadians(22);

            double xLeft = px - 9.0 * Math.cos(angle - phi);
            double yLeft = py - 9.0 * Math.sin(angle - phi);

            double xRight = px - 9.0 * Math.cos(angle + phi);
            double yRight = py - 9.0 * Math.sin(angle + phi);

            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(px, py);
            tri.lineTo(xLeft, yLeft);
            tri.lineTo(xRight, yRight);
            tri.closePath();

            Composite oldComp = g2d.getComposite();
            Stroke oldStroke = g2d.getStroke();
            Color oldColor = g2d.getColor();
            Object oldHint = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(color);
            g2d.fill(tri);

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint);
            g2d.setStroke(oldStroke);
            g2d.setColor(oldColor);
            g2d.setComposite(oldComp);
        }

        private static void drawTargetObject(HitObject hitObject, double circleRadius, Graphics2D g2d) {
            if (hitObject.getObjectType() == HitObject.ObjectType.SLIDER) {
                drawSlider(hitObject, hitObject, circleRadius, g2d);
            }

            Ellipse2D circle = new Ellipse2D.Double(
                    CANVAS_WIDTH * 0.5 - circleRadius * ZOOM_FACTOR,
                    CANVAS_HEIGHT * 0.5 - circleRadius * ZOOM_FACTOR,
                    circleRadius * 2 * ZOOM_FACTOR,
                    circleRadius * 2 * ZOOM_FACTOR
            );

            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1.5F));
            g2d.draw(circle);
        }

        private static void drawSlider(HitObject slider,
                                       HitObject focusObject,
                                       double circleRadius,
                                       Graphics2D g2d) {
            SliderPath sliderPath = new SliderPath(slider);
            Path2D.Double path = new Path2D.Double();
            int samples = Math.clamp((int) Math.ceil(sliderPath.expectedLength / 2), 1, 10000);

            for (int i = 0; i <= samples; i++) {
                Point point = sliderPath.positionAt((double) i / samples);
                double x = (point.x - focusObject.getX()) * ZOOM_FACTOR + CANVAS_WIDTH * 0.5;
                double y = (point.y - focusObject.getY()) * ZOOM_FACTOR + CANVAS_HEIGHT * 0.5;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }

            float bodyWidth = (float) (circleRadius * 2 * ZOOM_FACTOR);

            g2d.setColor(new Color(0, 0, 0, 50));
            g2d.setStroke(new BasicStroke(bodyWidth + 3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.draw(path);

            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(bodyWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.draw(path);

            g2d.setColor(new Color(0, 0, 0, 50));
            g2d.setStroke(new BasicStroke(1.5F));
            g2d.draw(path);
        }

        private record Point(double x, double y) {
        }

        /**
         * Builds the geometric path osu! uses for each supported slider curve type.
         */
        private static final class SliderPath {
            private final List<Point> points = new ArrayList<>();
            private final List<Double> cumulativeLength = new ArrayList<>();
            private final double expectedLength;

            private SliderPath(HitObject slider) {
                expectedLength = Math.max(0, slider.getLength());
                List<Point> controls = new ArrayList<>();
                controls.add(new Point(slider.getX(), slider.getY()));
                for (HitObject.ControlPoint point : slider.getControlPoints()) {
                    controls.add(new Point(point.x(), point.y()));
                }

                switch (slider.getCurveType() == null ? "L" : slider.getCurveType()) {
                    case "B" -> addBezierSegments(controls);
                    case "C" -> addCatmull(controls);
                    case "P" -> {
                        if (controls.size() == 3 && !addPerfectCurve(controls)) addBezier(controls);
                        else if (controls.size() != 3) addBezierSegments(controls);
                    }
                    default -> controls.forEach(this::addPoint);
                }
                if (points.isEmpty()) addPoint(new Point(slider.getX(), slider.getY()));
                extendToExpectedLength();
                calculateLengths();
            }

            private static Point interpolate(Point from, Point to, double weight) {
                return new Point(from.x + (to.x - from.x) * weight,
                        from.y + (to.y - from.y) * weight);
            }

            private static boolean same(Point a, Point b) {
                return Math.abs(a.x - b.x) < 1e-7 && Math.abs(a.y - b.y) < 1e-7;
            }

            private static double distance(Point a, Point b) {
                return Math.hypot(a.x - b.x, a.y - b.y);
            }

            private static double positiveAngle(double angle) {
                angle %= Math.PI * 2;
                return angle < 0 ? angle + Math.PI * 2 : angle;
            }

            private Point positionAt(double progress) {
                if (points.size() == 1 || expectedLength <= 0) return points.getFirst();
                double target = Math.clamp(progress, 0, 1) * expectedLength;
                int index = Collections.binarySearch(cumulativeLength, target);
                if (index >= 0) return points.get(index);
                index = -index - 1;
                if (index <= 0) return points.getFirst();
                if (index >= points.size()) return points.getLast();
                double from = cumulativeLength.get(index - 1);
                double to = cumulativeLength.get(index);
                if (to <= from) return points.get(index - 1);
                return interpolate(points.get(index - 1), points.get(index), (target - from) / (to - from));
            }

            private void addBezierSegments(List<Point> controls) {
                List<Point> segment = new ArrayList<>();
                segment.add(controls.getFirst());
                for (int i = 1; i < controls.size(); i++) {
                    Point current = controls.get(i);
                    segment.add(current);
                    if (i < controls.size() - 1 && same(current, controls.get(i + 1))) {
                        addBezier(segment);
                        segment = new ArrayList<>();
                        segment.add(current);
                        i++;
                    }
                }
                addBezier(segment);
            }

            private void addBezier(List<Point> controls) {
                if (controls.isEmpty()) return;
                if (controls.size() == 1) {
                    addPoint(controls.getFirst());
                    return;
                }
                double polygonLength = 0;
                for (int i = 1; i < controls.size(); i++) {
                    polygonLength += distance(controls.get(i - 1), controls.get(i));
                }
                int samples = Math.clamp((int) Math.ceil(polygonLength / 0.25), 25, 10000);
                for (int i = 0; i <= samples; i++) {
                    double t = (double) i / samples;
                    List<Point> work = new ArrayList<>(controls);
                    for (int level = work.size() - 1; level > 0; level--) {
                        for (int p = 0; p < level; p++) {
                            work.set(p, interpolate(work.get(p), work.get(p + 1), t));
                        }
                    }
                    addPoint(work.getFirst());
                }
            }

            private boolean addPerfectCurve(List<Point> controls) {
                Point a = controls.get(0);
                Point b = controls.get(1);
                Point c = controls.get(2);
                double determinant = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
                if (Math.abs(determinant) < 1e-7) return false;

                double a2 = a.x * a.x + a.y * a.y;
                double b2 = b.x * b.x + b.y * b.y;
                double c2 = c.x * c.x + c.y * c.y;
                Point center = new Point(
                        (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / determinant,
                        (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / determinant);
                double start = Math.atan2(a.y - center.y, a.x - center.x);
                double middle = Math.atan2(b.y - center.y, b.x - center.x);
                double end = Math.atan2(c.y - center.y, c.x - center.x);
                double sweep = positiveAngle(end - start);
                if (positiveAngle(middle - start) > sweep) sweep -= Math.PI * 2;
                double radius = distance(a, center);
                int samples = Math.clamp((int) Math.ceil(Math.abs(sweep * radius) / 2), 25, 1000);
                for (int i = 0; i <= samples; i++) {
                    double angle = start + sweep * i / samples;
                    addPoint(new Point(center.x + Math.cos(angle) * radius,
                            center.y + Math.sin(angle) * radius));
                }
                return true;
            }

            private void addCatmull(List<Point> controls) {
                if (controls.size() < 2) {
                    controls.forEach(this::addPoint);
                    return;
                }
                for (int i = 0; i < controls.size() - 1; i++) {
                    Point p0 = controls.get(Math.max(0, i - 1));
                    Point p1 = controls.get(i);
                    Point p2 = controls.get(i + 1);
                    Point p3 = controls.get(Math.min(controls.size() - 1, i + 2));
                    for (int sample = 0; sample <= 50; sample++) {
                        double t = sample / 50.0;
                        double t2 = t * t;
                        double t3 = t2 * t;
                        double x = 0.5 * (2 * p1.x + (-p0.x + p2.x) * t
                                + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
                                + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
                        double y = 0.5 * (2 * p1.y + (-p0.y + p2.y) * t
                                + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
                                + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
                        addPoint(new Point(x, y));
                    }
                }
            }

            private void extendToExpectedLength() {
                double currentLength = pathLength();
                if (expectedLength <= currentLength || points.size() < 2) return;
                int end = points.size() - 1;
                while (end > 0 && same(points.get(end), points.get(end - 1))) end--;
                if (end == 0) return;
                Point previous = points.get(end - 1);
                Point last = points.get(end);
                double segmentLength = distance(previous, last);
                if (segmentLength == 0) return;
                double extension = expectedLength - currentLength;
                addPoint(new Point(last.x + (last.x - previous.x) / segmentLength * extension,
                        last.y + (last.y - previous.y) / segmentLength * extension));
            }

            private double pathLength() {
                double length = 0;
                for (int i = 1; i < points.size(); i++) {
                    length += distance(points.get(i - 1), points.get(i));
                }
                return length;
            }

            private void calculateLengths() {
                cumulativeLength.add(0.0);
                for (int i = 1; i < points.size(); i++) {
                    cumulativeLength.add(cumulativeLength.getLast() + distance(points.get(i - 1), points.get(i)));
                }
            }

            private void addPoint(Point point) {
                if (points.isEmpty() || !same(points.getLast(), point)) points.add(point);
            }
        }
    }
}
