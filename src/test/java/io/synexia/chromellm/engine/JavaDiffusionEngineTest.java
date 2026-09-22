package io.synexia.chromellm.engine;
import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JavaDiffusionEngineTest {
    @Test void sameSeedIsDeterministic() {
        var request=new DiffusionRequest(16,16,3,8,42L,4f,0f,DiffusionCondition.classLabel(7));
        var engine=new JavaDiffusionEngine();
        assertArrayEquals(engine.generate(request).pixels(),engine.generate(request).pixels());
    }
    @Test void differentSeedsProduceDifferentImages() {
        var engine=new JavaDiffusionEngine();
        assertFalse(java.util.Arrays.equals(engine.generate(DiffusionRequest.standard(8,8,1L)).pixels(),
                engine.generate(DiffusionRequest.standard(8,8,2L)).pixels()));
    }
    @Test void outputIsNormalized() {
        for(float value:new JavaDiffusionEngine().generate(DiffusionRequest.standard(8,8,3L)).pixels()) assertTrue(value>=0f&&value<=1f);
    }
}
