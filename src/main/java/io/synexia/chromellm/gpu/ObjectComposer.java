package io.synexia.chromellm.gpu;

public final class ObjectComposer {
    private ObjectComposer() {}

    public static RgbaFrame place(
            ImageProcessor processor,
            RgbaFrame background,
            RgbaFrame object,
            int x,
            int y,
            float opacity) {
        byte[] canvas = new byte[background.byteSize()];
        byte[] objectPixels = object.pixels();

        for (int oy = 0; oy < object.height(); oy++) {
            int by = y + oy;
            if (by < 0 || by >= background.height()) continue;
            for (int ox = 0; ox < object.width(); ox++) {
                int bx = x + ox;
                if (bx < 0 || bx >= background.width()) continue;
                int source = (oy * object.width() + ox) * 4;
                int target = (by * background.width() + bx) * 4;
                canvas[target] = objectPixels[source];
                canvas[target + 1] = objectPixels[source + 1];
                canvas[target + 2] = objectPixels[source + 2];
                canvas[target + 3] = objectPixels[source + 3];
            }
        }

        return processor.blend(
                background,
                new RgbaFrame(background.width(), background.height(), canvas),
                opacity);
    }
}
