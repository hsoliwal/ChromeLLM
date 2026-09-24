package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.CpuImageProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransformSpecParserTest {
    private final TransformSpecParser parser = new TransformSpecParser(new CpuImageProcessor());

    @Test
    void parsesMixedPipeline() {
        TransformChain chain = parser.parsePipeline(
                "grayscale,unsharp:2:1.2:3,temporal-denoise:0.25:0.35:4,stabilize:4:2:0.5:0.4");
        assertEquals(4, chain.size());
        assertTrue(chain.transforms().get(1) instanceof UnsharpMaskTransform);
        assertTrue(chain.transforms().get(2) instanceof TemporalDenoiseTransform);
        assertTrue(chain.transforms().get(3) instanceof StabilizationTransform);
    }

    @Test
    void parsesRegionAndRange() {
        assertTrue(parser.parseTransform("region:1:2:3:4:gamma:1.1") instanceof RegionTransform);
        assertTrue(parser.parseTransform("range:10:20:grayscale") instanceof FrameRangeTransform);
    }

    @Test
    void rejectsMalformedRegion() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseTransform("region:1:2"));
    }
}
