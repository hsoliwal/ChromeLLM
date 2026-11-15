package io.synexia.chromellm.webmcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Read-only, fixed-tool Chrome Native Messaging host for approved build metadata. */
public final class AgentHost {
    private AgentHost() { }

    public static void main(String[] args) throws IOException {
        // jpackage --arguments can be skipped when Chrome supplies an extension origin.
        // The native host may instead receive its fixed config as a packaged JVM property.
        String pinnedConfig = System.getProperty("chromellm.agent.config", "");
        String[] launch = args;
        if (!pinnedConfig.isBlank()) {
            launch = new String[args.length + 1];
            launch[0] = pinnedConfig;
            System.arraycopy(args, 0, launch, 1, args.length);
        }
        if (launch.length < 1 || launch.length > 3) {
            System.err.println("invalid native host arguments");
            System.exit(2);
            return;
        }
        ApprovedInventory inventory = new ApprovedInventory(Path.of(launch[0]));
        if (!validCaller(launch, inventory.allowedOrigin())) {
            System.err.println("native caller not authorized");
            System.exit(2);
            return;
        }
        while (true) {
            String message;
            try { message = NativeFraming.read(System.in); }
            catch (IOException failure) {
                NativeFraming.write(System.out, error("INVALID_FRAME"));
                return;
            }
            if (message == null) return;
            String result;
            try {
                Map<String, String> request = JsonStrings.parse(message);
                String op = request.get("op");
                result = switch (op == null ? "" : op) {
                    case "hello" -> {
                        if (request.size() != 1) throw new IllegalArgumentException("unknown keys");
                        yield "{\"ok\":true,\"protocol\":\"m3-native-v0\",\"access\":\"read-only\"}";
                    }
                    case "inventory" -> {
                        if (request.size() != 2 || !request.containsKey("repo")) {
                            throw new IllegalArgumentException("unknown keys");
                        }
                        yield inventory.inspect(request.get("repo"));
                    }
                    default -> throw new IllegalArgumentException("unsupported operation");
                };
            } catch (IllegalArgumentException e) {
                result = error("INVALID_REQUEST");
            } catch (IOException e) {
                result = error("READ_FAILED");
            }
            NativeFraming.write(System.out, result);
        }
    }

    /** Chrome appends extension origin (and optionally a Windows parent-window handle). */
    private static boolean validCaller(String[] args, String configuredOrigin) {
        if (args.length == 1) return true; // direct offline test/operator inspection
        String expected = canonicalOrigin(configuredOrigin);
        if (expected == null) return false;
        boolean originSeen = false, windowSeen = false;
        for (int i = 1; i < args.length; i++) {
            String value = args[i];
            String origin = canonicalOrigin(value);
            if (origin != null) {
                if (originSeen || !expected.equals(origin)) return false;
                originSeen = true;
            } else if (value.matches("--parent-window=[0-9]{1,20}")) {
                if (windowSeen) return false;
                windowSeen = true;
            } else {
                return false;
            }
        }
        return originSeen;
    }

    private static String canonicalOrigin(String value) {
        if (value == null) return null;
        String origin = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return origin.matches("chrome-extension://[a-p]{32}") ? origin : null;
    }

    private static String error(String reason) {
        return "{\"ok\":false,\"error\":" + JsonStrings.quote(reason) + "}";
    }
}
