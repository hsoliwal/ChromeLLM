package io.synexia.chromellm.gpu;

public enum PixelOperation {
    COPY(0),
    INVERT(1),
    GRAYSCALE(2),
    BRIGHTNESS_CONTRAST(3),
    GAMMA(4),
    THRESHOLD(5),
    CHANNEL_SCALE(6),
    SOBEL_EDGE(7),
    BOX_BLUR(8);

    private final int nativeCode;
    PixelOperation(int nativeCode) { this.nativeCode = nativeCode; }
    public int nativeCode() { return nativeCode; }
}
