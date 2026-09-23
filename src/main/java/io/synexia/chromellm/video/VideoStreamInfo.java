package io.synexia.chromellm.video;

public record VideoStreamInfo(int width, int height, double framesPerSecond) {
    public VideoStreamInfo {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("video dimensions must be positive");
        if (!Double.isFinite(framesPerSecond) || framesPerSecond <= 0d) {
            throw new IllegalArgumentException("framesPerSecond must be positive");
        }
    }

    public int rgbaFrameBytes() {
        return Math.multiplyExact(Math.multiplyExact(width, height), 4);
    }
}
