package io.synexia.chromellm.video;

@FunctionalInterface
public interface ProcessingControl {
    ProcessingControl NEVER_CANCELLED = () -> false;

    boolean isCancellationRequested();

    default void checkCancelled() {
        if (isCancellationRequested()) {
            throw new ProcessingCancelledException("media processing cancelled");
        }
    }
}
