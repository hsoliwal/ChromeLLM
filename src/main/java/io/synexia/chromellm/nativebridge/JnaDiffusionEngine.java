package io.synexia.chromellm.nativebridge;

import com.sun.jna.Pointer;
import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
import io.synexia.chromellm.engine.DiffusionEngine;
import java.util.Map;

public final class JnaDiffusionEngine implements DiffusionEngine {
    private final NativeDiffusionLibrary library;
    public JnaDiffusionEngine() { this(NativeDiffusionLibrary.INSTANCE); }
    JnaDiffusionEngine(NativeDiffusionLibrary library) { this.library = library; }

    @Override public DiffusionResult generate(DiffusionRequest request) {
        Pointer handle = library.diffusion_create(request.width(), request.height(), request.channels(), request.seed());
        if (handle == null) throw new IllegalStateException("native diffusion_create failed: " + library.diffusion_last_error());
        try {
            DiffusionCondition c = request.condition();
            float[] data = c.data();
            float[] output = new float[request.width() * request.height() * request.channels()];
            int rc = library.diffusion_generate(handle, c.mode().ordinal(), data, data.length, c.width(), c.height(), c.channels(),
                    c.classId(), request.steps(), request.guidanceScale(), request.eta(), output, output.length);
            if (rc != 0) throw new IllegalStateException("native diffusion_generate failed: " + library.diffusion_last_error());
            return new DiffusionResult(request.width(), request.height(), request.channels(), output, request.seed(),
                    Map.of("engine","jna-native","condition",c.mode().name()));
        } finally {
            library.diffusion_destroy(handle);
        }
    }
}
