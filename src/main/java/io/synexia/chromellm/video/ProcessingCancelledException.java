package io.synexia.chromellm.video;

public final class ProcessingCancelledException extends RuntimeException {
    public ProcessingCancelledException(String message) {
        super(message);
    }
}
