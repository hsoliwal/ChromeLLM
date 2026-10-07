package io.synexia.chromellm.video;

public enum VideoCodec {
    H264("libx264"),
    H265("libx265"),
    AV1("libaom-av1"),
    VP9("libvpx-vp9");

    private final String ffmpegName;

    VideoCodec(String ffmpegName) {
        this.ffmpegName = ffmpegName;
    }

    public String ffmpegName() {
        return ffmpegName;
    }
}
