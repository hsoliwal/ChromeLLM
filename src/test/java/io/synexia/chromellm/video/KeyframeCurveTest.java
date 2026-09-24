package io.synexia.chromellm.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyframeCurveTest {
    @Test
    void linearlyInterpolatesBetweenKeyframes() {
        var curve = new KeyframeCurve(List.of(
                new Keyframe(0, 0f, Easing.LINEAR),
                new Keyframe(10, 1f, Easing.LINEAR)));
        assertEquals(0.5f, curve.valueAt(5), 0.0001f);
    }

    @Test
    void holdKeepsLeftValueButExactRightKeyframeWins() {
        var curve = new KeyframeCurve(List.of(
                new Keyframe(0, 2f, Easing.HOLD),
                new Keyframe(10, 8f, Easing.LINEAR),
                new Keyframe(20, 10f, Easing.LINEAR)));
        assertEquals(2f, curve.valueAt(9), 0f);
        assertEquals(8f, curve.valueAt(10), 0f);
    }

    @Test
    void sortsInputAndRejectsDuplicateFrames() {
        var curve = new KeyframeCurve(List.of(
                new Keyframe(10, 1f, Easing.LINEAR),
                new Keyframe(0, 0f, Easing.LINEAR)));
        assertEquals(0f, curve.valueAt(0), 0f);
        assertThrows(IllegalArgumentException.class, () -> new KeyframeCurve(List.of(
                new Keyframe(1, 0f, Easing.LINEAR),
                new Keyframe(1, 1f, Easing.LINEAR))));
    }
}
