package io.synexia.chromellm.video;

public record FrameRegion(int x, int y, int width, int height) {
    public FrameRegion {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("region dimensions must be positive");
    }

    public FrameRegion clampTo(int frameWidth, int frameHeight) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(frameWidth, x + width);
        int bottom = Math.min(frameHeight, y + height);
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("region does not overlap frame");
        }
        return new FrameRegion(left, top, right - left, bottom - top);
    }
}
