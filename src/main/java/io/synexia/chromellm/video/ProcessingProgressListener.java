package io.synexia.chromellm.video;

@FunctionalInterface
public interface ProcessingProgressListener {
    ProcessingProgressListener NONE = progress -> { };

    void onProgress(ProcessingProgress progress);
}
