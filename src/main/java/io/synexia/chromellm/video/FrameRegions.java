package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class FrameRegions {
    private FrameRegions() {
    }

    public static RgbaFrame crop(RgbaFrame frame, FrameRegion region) {
        FrameRegion r = region.clampTo(frame.width(), frame.height());
        byte[] source = frame.pixels();
        byte[] output = new byte[r.width() * r.height() * 4];
        for (int y = 0; y < r.height(); y++) {
            int sourceOffset = ((r.y() + y) * frame.width() + r.x()) * 4;
            int targetOffset = y * r.width() * 4;
            System.arraycopy(source, sourceOffset, output, targetOffset, r.width() * 4);
        }
        return new RgbaFrame(r.width(), r.height(), output);
    }

    public static RgbaFrame paste(RgbaFrame frame, RgbaFrame patch, int x, int y) {
        byte[] output = frame.pixels();
        byte[] source = patch.pixels();
        for (int py = 0; py < patch.height(); py++) {
            int fy = y + py;
            if (fy < 0 || fy >= frame.height()) continue;
            for (int px = 0; px < patch.width(); px++) {
                int fx = x + px;
                if (fx < 0 || fx >= frame.width()) continue;
                int src = (py * patch.width() + px) * 4;
                int dst = (fy * frame.width() + fx) * 4;
                output[dst] = source[src];
                output[dst + 1] = source[src + 1];
                output[dst + 2] = source[src + 2];
                output[dst + 3] = source[src + 3];
            }
        }
        return new RgbaFrame(frame.width(), frame.height(), output);
    }
}
