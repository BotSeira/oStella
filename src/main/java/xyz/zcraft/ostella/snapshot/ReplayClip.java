package xyz.zcraft.ostella.snapshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;

public record ReplayClip(SnapshotScene center, double before, double after) {
    public ReplayClip {
        if (!Double.isFinite(before) || !Double.isFinite(after) || before < 0 || after < 0
                || before + after <= 0 || before + after > 6)
            throw new IllegalArgumentException("Invalid GIF window");
    }

    public double start() {
        return Math.max(0, center.time() - before * 1000 * center.clockRate());
    }

    public double end() {
        return Math.min(center.maximumTime(), center.time() + after * 1000 * center.clockRate());
    }

    public byte[] render(BufferedImage background) {
        // Centisecond delays are native to GIF. Round down to keep the encoded duration <= 6s.
        int duration = Math.max(1, (int) Math.floor((end() - start()) / center.clockRate() / 10 + 1e-8));
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            final int frameRateFactor = 6;
            for (int elapsed = 0; elapsed < duration; elapsed += frameRateFactor) {
                long time = Math.round(start() + elapsed * 10 * center.clockRate());
                var scene = new SnapshotScene(center.analyze(), new SnapshotRequest(SnapshotRequest.Kind.TIME, time, 0), true);
                var frame = SnapshotRenderer.renderFrame(scene, background, 720, 405);
                var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
                String format = metadata.getNativeMetadataFormatName();
                var tree = (IIOMetadataNode) metadata.getAsTree(format);
                var control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", Integer.toString(Math.min(frameRateFactor, duration - elapsed)));
                control.setAttribute("transparentColorIndex", "0");
                if (elapsed == 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0});
                    extensions.appendChild(loop);
                    tree.appendChild(extensions);
                }
                metadata.setFromTree(format, tree);
                writer.writeToSequence(new IIOImage(frame, null, metadata), null);
            }
            writer.endWriteSequence();
            output.flush();
            return GifOptimizer.compress(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode replay GIF", e);
        } finally {
            writer.dispose();
        }
    }

    public record Window(double before, double after) {
        public static Window parse(String before, String after) {
            BigDecimal b = seconds(before == null ? "3" : before);
            BigDecimal a = seconds(after == null ? "1" : after);
            if (b.add(a).signum() <= 0 || b.add(a).compareTo(new BigDecimal("6")) > 0)
                throw new IllegalArgumentException("GIF window must be greater than 0 and at most 6 seconds");
            return new Window(b.doubleValue(), a.doubleValue());
        }

        private static BigDecimal seconds(String value) {
            if (!value.matches("[0-9]+(?:\\.[0-9]+)?"))
                throw new IllegalArgumentException("GIF window must contain non-negative seconds");
            return new BigDecimal(value);
        }
    }
}
