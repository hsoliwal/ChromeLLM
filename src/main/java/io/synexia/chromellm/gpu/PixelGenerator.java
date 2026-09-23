package io.synexia.chromellm.gpu;

public enum PixelGenerator {
    SOLID(0),
    GRADIENT(1),
    NOISE(2),
    PLASMA(3),
    CHECKERBOARD(4);

    private final int nativeCode;
    PixelGenerator(int nativeCode) { this.nativeCode = nativeCode; }
    public int nativeCode() { return nativeCode; }
}
