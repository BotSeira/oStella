package xyz.zcraft.ostella.snapshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Lossless delta encoding for the full, opaque frames produced by ReplayClip. */
final class GifOptimizer {
    private GifOptimizer() {}

    static byte[] compress(byte[] original) throws IOException {
        var reader = ImageIO.getImageReadersByFormatName("gif").next();
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original));
             var bytes = new ByteArrayOutputStream();
             var output = new MemoryCacheImageOutputStream(bytes)) {
            reader.setInput(input);
            writer.setOutput(output);
            writer.prepareWriteSequence(reader.getStreamMetadata());
            int[] previous = null;
            for (int i = 0, count = reader.getNumImages(true); i < count; i++) {
                var frame = reader.read(i);
                int width = frame.getWidth(), height = frame.getHeight();
                int[] pixels = frame.getRGB(0, 0, width, height, null, 0, width);
                var metadata = writer.convertImageMetadata(reader.getImageMetadata(i),
                        ImageTypeSpecifier.createFromRenderedImage(frame), null);
                String format = metadata.getNativeMetadataFormatName();
                var tree = (IIOMetadataNode) metadata.getAsTree(format);
                if (previous != null) {
                    int left = width, top = height, right = -1, bottom = -1;
                    var palette = (IndexColorModel) frame.getColorModel();
                    var raster = frame.getRaster();
                    boolean[] used = new boolean[palette.getMapSize()];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int offset = y * width + x;
                            if (pixels[offset] == previous[offset]) continue;
                            left = Math.min(left, x);
                            top = Math.min(top, y);
                            right = Math.max(right, x);
                            bottom = Math.max(bottom, y);
                            used[raster.getSample(x, y, 0)] = true;
                        }
                    }
                    // An unchanged frame still carries its original delay.
                    if (right < 0) left = top = right = bottom = 0;
                    int transparent = -1;
                    for (int index = 0; index < used.length; index++) {
                        if (!used[index]) {
                            transparent = index;
                            break;
                        }
                    }
                    // If all palette entries changed, keep an opaque cropped frame.
                    // Never discard a colour to make room for transparency.
                    int cropWidth = right - left + 1, cropHeight = bottom - top + 1;
                    var cropped = new BufferedImage(cropWidth, cropHeight,
                            BufferedImage.TYPE_BYTE_INDEXED, palette);
                    for (int y = 0; y < cropHeight; y++) {
                        for (int x = 0; x < cropWidth; x++) {
                            int offset = (top + y) * width + left + x;
                            int index = transparent >= 0 && pixels[offset] == previous[offset]
                                    ? transparent : raster.getSample(left + x, top + y, 0);
                            cropped.getRaster().setSample(x, y, 0, index);
                        }
                    }
                    var descriptor = (IIOMetadataNode) tree.getElementsByTagName("ImageDescriptor").item(0);
                    descriptor.setAttribute("imageLeftPosition", Integer.toString(left));
                    descriptor.setAttribute("imageTopPosition", Integer.toString(top));
                    descriptor.setAttribute("imageWidth", Integer.toString(cropWidth));
                    descriptor.setAttribute("imageHeight", Integer.toString(cropHeight));
                    var control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                    control.setAttribute("transparentColorFlag", transparent >= 0 ? "TRUE" : "FALSE");
                    control.setAttribute("transparentColorIndex", Integer.toString(Math.max(0, transparent)));
                    frame = cropped;
                }
                // Retain the composited image for the next delta; frame zero resets each loop.
                var control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", "doNotDispose");
                metadata.setFromTree(format, tree);
                writer.writeToSequence(new IIOImage(frame, null, metadata), null);
                previous = pixels;
            }
            writer.endWriteSequence();
            output.flush();
            byte[] compressed = bytes.toByteArray();
            return compressed.length < original.length ? compressed : original;
        } finally {
            reader.dispose();
            writer.dispose();
        }
    }
}
