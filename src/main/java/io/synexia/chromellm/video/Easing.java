package io.synexia.chromellm.video;

public enum Easing {
    HOLD {
        @Override public float apply(float value) { return 0f; }
    },
    LINEAR {
        @Override public float apply(float value) { return value; }
    },
    EASE_IN {
        @Override public float apply(float value) { return value * value; }
    },
    EASE_OUT {
        @Override public float apply(float value) {
            float inverse = 1f - value;
            return 1f - inverse * inverse;
        }
    },
    EASE_IN_OUT {
        @Override public float apply(float value) {
            return value < 0.5f
                    ? 2f * value * value
                    : 1f - (float)Math.pow(-2f * value + 2f, 2d) / 2f;
        }
    };

    public abstract float apply(float value);
}
