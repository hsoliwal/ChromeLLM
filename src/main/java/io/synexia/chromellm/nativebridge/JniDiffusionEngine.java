package io.synexia.chromellm.nativebridge;

import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
import io.synexia.chromellm.engine.DiffusionEngine;
import java.util.Map;

public final class JniDiffusionEngine implements DiffusionEngine {
    @Override public DiffusionResult generate(DiffusionRequest request) {
        DiffusionCondition c = request.condition();
        float[] output = JniDiffusion.generate(request.width(), request.height(), request.channels(), request.steps(), request.seed(),
                request.guidanceScale(), request.eta(), c.mode().ordinal(), c.data(), c.width(), c.height(), c.channels(), c.classId());
        return new DiffusionResult(request.width(), request.height(), request.channels(), output, request.seed(),
                Map.of("engine","jni-native","condition",c.mode().name()));
    }
}
