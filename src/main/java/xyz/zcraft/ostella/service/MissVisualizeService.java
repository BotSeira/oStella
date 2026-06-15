package xyz.zcraft.ostella.service;

import xyz.zcraft.ostella.network.ApiException;
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
import java.util.LinkedList;
import java.util.List;

public class MissVisualizeService {
    public static final double TIMING_INDICATOR_PERCENTAGE = 2;

    private static final int CANVAS_WIDTH = 512;
    private static final int CANVAS_HEIGHT = 384;
    private static final double ZOOM_FACTOR = 1.5;
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
                .toList();

        if (missIndex <= 0 || missIndex > missEvents.size()) {
            throw new ApiException(ErrorCode.ILLEGAL_ARGUMENT, "Invalid miss index: " + missIndex + ", should be 1-" + missEvents.size());
        }

        final HitEvent targetMiss = missEvents.get(missIndex - 1);

        final var keyFrames = replayAnalyze.replay().timedKeyFrames();

        return ImageHelper.drawMiss(
                missIndex,
                targetMiss.hitObject(),
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
                                       HitObject hitObject,
                                       List<OsuReplay.TimedKeyFrame> keyFrames,
                                       OsuBeatmap beatmap,
                                       DifficultyAttribute diff) {
            final double circleRadius = diff.getCircleRadiusInPixel();

            final LinkedList<Long> hitTimes = new LinkedList<>();

            BufferedImage canvas = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);

            Graphics2D g2d = canvas.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

            drawNearbyCircles(hitObject, beatmap, circleRadius, g2d);

            drawTargetCircle(circleRadius, g2d);

            drawCursorPath(hitObject, keyFrames, diff, g2d);

            drawFramePoints(hitObject, keyFrames, g2d, hitTimes);

            drawText(missIndex, hitObject, beatmap, g2d);

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

        private static void drawNearbyCircles(HitObject hitObject, OsuBeatmap beatmap, double circleRadius, Graphics2D g2d) {
            beatmap.getHitObjects().stream()
                    .filter(obj -> obj.getObjectType() != HitObject.ObjectType.SPINNER)
                    .filter(obj -> obj.getTime() <= hitObject.getTime() + WINDOW_MILLIS
                            && obj.getTime() >= hitObject.getTime() - WINDOW_MILLIS)
                    .forEach(obj -> {
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

        private static void drawText(int missIndex, HitObject hitObject, OsuBeatmap beatmap, Graphics2D g2d) {
            g2d.setColor(Color.BLACK);

            final Duration duration = Duration.of(hitObject.getTime(), ChronoUnit.MILLIS);
            String missInfo = "#" + missIndex + " Miss: " + hitObject.getObjectType() + " @" +
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

        private static void drawTargetCircle(double circleRadius, Graphics2D g2d) {
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
    }
}
