package xyz.zcraft.ostella.snapshot;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Density-aware sprite canvases/origins, skin.ini colours and font spacing.
 */
public final class SnapshotSkin {
    private static final String ROOT = "/snapshot-skin/whitecat/";
    private final Map<String, Sprite> sprites = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> tinted = new ConcurrentHashMap<>();
    private final Map<String, Point2D.Double> judgementOrigins = new ConcurrentHashMap<>();
    private final Map<String, String> settings = new HashMap<>();
    private final List<Color> colours;
    public SnapshotSkin() {
        try (InputStream stream = getClass().getResourceAsStream(ROOT + "skin.ini")) {
            if (stream == null) throw new IOException("WhiteCat skin.ini is missing");
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String clean = line.split("//", 2)[0].trim();
                int colon = clean.indexOf(':');
                if (colon > 0) settings.put(clean.substring(0, colon).trim(), clean.substring(colon + 1).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load bundled snapshot skin", e);
        }
        var list = new java.util.ArrayList<Color>();
        for (int i = 1; i <= 8; i++) if (settings.containsKey("Combo" + i)) list.add(colour("Combo" + i, Color.WHITE));
        colours = list.isEmpty() ? List.of(Color.WHITE) : List.copyOf(list);
    }

    private static Point2D.Double visibleCentre(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        // Keep the source canvas intact, but anchor judgements by their visible pixels.
        // A fully transparent judgement (WhiteCat's hit300) remains an empty sprite.
        return maxX < minX ? new Point2D.Double(image.getWidth() / 2.0, image.getHeight() / 2.0)
                : new Point2D.Double((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0);
    }

    private static BufferedImage tint(BufferedImage original, Color colour) {
        var image = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = original.getRGB(x, y);
                int r = (pixel >> 16 & 255) * colour.getRed() / 255;
                int g = (pixel >> 8 & 255) * colour.getGreen() / 255;
                int b = (pixel & 255) * colour.getBlue() / 255;
                image.setRGB(x, y, pixel & 0xff000000 | r << 16 | g << 8 | b);
            }
        return image;
    }

    public Color combo(int index) {
        return colours.get(Math.floorMod(index, colours.size()));
    }

    public Color colour(String setting, Color fallback) {
        String value = settings.get(setting);
        if (value == null) return fallback;
        String[] channels = value.split(",");
        return new Color(Integer.parseInt(channels[0].trim()), Integer.parseInt(channels[1].trim()), Integer.parseInt(channels[2].trim()));
    }

    public String setting(String key, String fallback) {
        return settings.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback) {
        return Integer.parseInt(setting(key, Integer.toString(fallback)));
    }

    public Sprite sprite(String name) {
        return sprites.computeIfAbsent(name, key -> {
            try (var stream = getClass().getResourceAsStream(ROOT + key + "@2x.png")) {
                if (stream != null) return new Sprite(ImageIO.read(stream), 2);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read skin sprite " + key + "@2x", e);
            }
            try (var stream = getClass().getResourceAsStream(ROOT + key + ".png")) {
                if (stream != null) return new Sprite(ImageIO.read(stream), 1);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read skin sprite " + key, e);
            }
            return new Sprite(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), 1);
        });
    }

    public void draw(Graphics2D graphics, String name, double x, double y, double scale, double alpha, Color tint, double rotation) {
        draw(graphics, name, x, y, scale, alpha, tint, rotation, null);
    }

    public void drawJudgement(Graphics2D graphics, String name, double x, double y, double scale, double alpha) {
        var origin = judgementOrigins.computeIfAbsent(name, key -> visibleCentre(sprite(key).image()));
        draw(graphics, name, x, y, scale, alpha, null, 0, origin);
    }

    private void draw(Graphics2D graphics, String name, double x, double y, double scale, double alpha,
                      Color tint, double rotation, Point2D.Double origin) {
        if (alpha <= 0 || scale <= 0) return;
        var sprite = sprite(name);
        BufferedImage image = sprite.image();
        if (tint != null && !tint.equals(Color.WHITE)) {
            String key = name + ":" + tint.getRGB();
            image = tinted.computeIfAbsent(key, _ -> tint(sprite.image(), tint));
        }
        var g = (Graphics2D) graphics.create();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) SnapshotScene.clamp(alpha)));
            var transform = new AffineTransform();
            transform.translate(x, y);
            transform.rotate(rotation);
            transform.scale(scale / sprite.density(), scale / sprite.density());
            transform.translate(origin == null ? -image.getWidth() / 2.0 : -origin.x,
                    origin == null ? -image.getHeight() / 2.0 : -origin.y);
            g.drawImage(image, transform, null);
        } finally {
            g.dispose();
        }
    }

    public void number(Graphics2D g, String text, String prefix, int overlap, double x, double y, double scale, double alpha) {
        double width = 0;
        for (int i = 0; i < text.length(); i++)
            width += sprite(prefix + "-" + text.charAt(i)).width() - (i == 0 ? 0 : overlap);
        double left = x - width * scale / 2;
        for (int i = 0; i < text.length(); i++) {
            String name = prefix + "-" + text.charAt(i);
            double partWidth = sprite(name).width();
            draw(g, name, left + partWidth * scale / 2, y, scale, alpha, null, 0);
            left += (partWidth - overlap) * scale;
        }
    }

    public record Sprite(BufferedImage image, double density) {
        double width() {
            return image.getWidth() / density;
        }

        double height() {
            return image.getHeight() / density;
        }
    }
}
