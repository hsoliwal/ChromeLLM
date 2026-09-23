package io.synexia.chromellm.gpu;

public interface ImageProcessor extends AutoCloseable {
    RgbaFrame process(RgbaFrame input, PixelOperation operation, PixelParameters parameters);
    RgbaFrame blend(RgbaFrame base, RgbaFrame overlay, float opacity);
    RgbaFrame generate(int width, int height, PixelGenerator generator, long seed, PixelParameters parameters);
    String backendName();
    boolean hardwareAccelerated();
    @Override default void close() {}
}
