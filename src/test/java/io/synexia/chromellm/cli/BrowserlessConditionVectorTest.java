// SPDX-License-Identifier: MIT
package io.synexia.chromellm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** Exact browser-free CLI input contract; no native engine or model is loaded. */
public final class BrowserlessConditionVectorTest {
    @Test
    void sixteenFiniteValuesRoundTrip() throws Exception {
        String csv = "0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1,0,1,0.5,0.01,0";
        float[] actual = (float[]) vector().invoke(null, csv);
        assertArrayEquals(new float[] {
            0f, .1f, .2f, .3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 0f, 1f, .5f, .01f, 0f
        }, actual);

        Object options = parse("--engine", "java", "--width", "32",
                "--condition-vector", csv);
        var field = options.getClass().getDeclaredField("conditionVector");
        field.setAccessible(true);
        assertEquals(csv, field.get(options));
        Method main = Main.class.getMethod("main", String[].class);
        assertNotNull(main);
        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
    }

    @Test
    void malformedNumericsAndLengthsRefuseBeforeEngineExecution() throws Exception {
        Method parseVector = vector();
        String good = "0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1,0,1,0.5,0.01,0";
        String[] invalid = {
            "", "0,0.1", good + ",0",
            good.replace("0.2", "NaN"),
            good.replace("0.2", "Infinity"),
            good.replace("0.2", "-0.01"),
            good.replace("0.2", "1.01"),
            good.replace("0.2", "not-a-number")
        };
        for (String candidate : invalid) {
            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> parseVector.invoke(null, candidate), candidate);
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test
    void formerBrowserFlagsRemainUnsupported() throws Exception {
        for (String[] args : new String[][] {
                {"--prompt", "hello"},
                {"--chrome-base", "http://127.0.0.1:11435"},
                {"--condition-vector"},
                {"--condition-vector", ""},
                {"--engine"}
        }) {
            Method method = argumentsParser();
            InvocationTargetException failure = assertThrows(
                    InvocationTargetException.class,
                    () -> method.invoke(null, (Object) args));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    private static Method vector() throws Exception {
        Method method = Main.class.getDeclaredMethod("parseVector", String.class);
        method.setAccessible(true);
        return method;
    }

    private static Method argumentsParser() throws Exception {
        Class<?> type = Class.forName(Main.class.getName() + "$Arguments");
        Method method = type.getDeclaredMethod("parse", String[].class);
        method.setAccessible(true);
        return method;
    }

    private static Object parse(String... args) throws Exception {
        return argumentsParser().invoke(null, (Object) args);
    }
}
