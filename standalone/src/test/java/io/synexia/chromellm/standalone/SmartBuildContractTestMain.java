// SPDX-License-Identifier: MIT
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline Maven smart-build recipe, replay, isolation and source-pin contracts. */
public final class SmartBuildContractTestMain {
    private static int checks;
    private SmartBuildContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path temporaryRoot = Files.createTempDirectory("chromellm-smart-test-");
        Path root = temporaryRoot.toRealPath();
        try {
            Path target = Files.createDirectories(root.resolve("target"));
            offlineOutputRefusal(root);
            Files.writeString(root.resolve("prompt.txt"), "मॉडल prompt", StandardCharsets.UTF_8);
            Path entrypoint = Files.createDirectory(root.resolve("entrypoint"));
            Files.writeString(entrypoint.resolve("prompt.txt"), "single main prompt", StandardCharsets.UTF_8);
            mainRunsOneInference(entrypoint, Files.createDirectory(entrypoint.resolve("target")));
            if (args.length == 1 && args[0].equals("main-once")) return;
            Files.writeString(root.resolve("recipe.properties"), String.join("\n",
                    "promptFile=prompt.txt", "backend=cli", "llamaBin=/explicit/llama-cli",
                    "model=/explicit/model.gguf", "maxTokens=8", "timeoutSeconds=10") + "\n");
            AtomicInteger requests = new AtomicInteger();
            SmartBuildMain.ProviderFactory provider = (options, ignored) -> {
                check(options.get("--llama-bin").equals(root.resolve("/explicit/llama-cli").normalize().toString()),
                        "launcher selection lost");
                check(options.get("--model").equals(root.resolve("/explicit/model.gguf").normalize().toString()),
                        "model selection lost");
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        requests.incrementAndGet();
                        check(request.maxTokens() == 8, "max token policy lost");
                        check(request.timeout().toSeconds() == 10, "timeout policy lost");
                        return new ModelResponse("candidate", id(), hash(request.prompt()));
                    }
                };
            };
            Path output = SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider);
            check(Files.readString(output).equals("candidate"), "model candidate changed");
            check(output.startsWith(target.resolve("smart-build")), "write escaped target");
            Path repeated = SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider);
            check(output.equals(repeated) && requests.get() == 2, "idempotent replay contract failed");
            Path canonicalWorkspaceOutput = SmartBuildMain.run(Path.of("recipe.properties"),
                    root, temporaryRoot.resolve("target"), Map.of(), provider);
            check(output.equals(canonicalWorkspaceOutput) && requests.get() == 3,
                    "canonical workspace and original temporary target disagree");
            try (var entries = Files.list(target.resolve("smart-build"))) {
                check(entries.count() == 2, "output and receipt must be separate artifacts");
            }
            SmartBuildMain.ProviderFactory divergent = (options, env) -> new ModelBackend() {
                public String id() { return "llama.cpp-cli"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("divergent", id(), hash(request.prompt()));
                }
            };
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), divergent));
            check(Files.readString(output).equals("candidate"), "conflict overwritten candidate");
            AtomicInteger invalidTargetOpens = new AtomicInteger();
            SmartBuildMain.ProviderFactory forbidden = (options, env) -> {
                invalidTargetOpens.incrementAndGet();
                throw new AssertionError("invalid output location admitted model preparation");
            };
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root,
                    root.resolve("not-target"), Map.of(), forbidden));
            check(invalidTargetOpens.get() == 0, "invalid target performed provider work");
            // A sparse prior artifact must be rejected without allocating its claimed size.
            long oversized = 256L * 1024 * 1024;
            try (FileChannel file = FileChannel.open(output, StandardOpenOption.WRITE)) {
                file.position(oversized);
                file.write(ByteBuffer.wrap(new byte[] {0}));
            }
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            check(Files.size(output) == oversized + 1, "oversized collision was overwritten");
            Files.writeString(root.resolve("recipe.properties"), "promptFile=../outside.txt\nbackend=cli\n");
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=gemini\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nbackend=jni\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nunknown=1\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nmaxTokens=32769\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\n"
                    + "llamaBin=bin/llama-cli\nmodel=models/model.gguf\n");
            int beforeInvalidTarget = requests.get();
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, root, Map.of(), provider));
            check(requests.get() == beforeInvalidTarget, "invalid target opened provider");
            SmartBuildMain.ProviderFactory relative = (options, env) -> {
                check(options.get("--llama-bin").equals(root.resolve("bin/llama-cli").toString()), "relative launcher root");
                check(options.get("--model").equals(root.resolve("models/model.gguf").toString()), "relative model root");
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        return new ModelResponse("relative-candidate", id(), hash(request.prompt()));
                    }
                };
            };
            SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), relative);
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\n"
                    + "llamaBin=bin/llama-cli\nmodelUrl=https://example.invalid/model\n"
                    + "modelSha256=" + "0".repeat(64) + "\nmodelCacheDir=offline-cache\n");
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root,
                    root.resolve("not-target"), Map.of(), forbidden, true));
            check(!Files.exists(root.resolve("offline-cache")), "invalid target prepared the offline cache");
            check(invalidTargetOpens.get() == 0, "invalid offline target opened provider");
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider, true));
            check(requests.get() == beforeInvalidTarget, "offline cache miss opened provider");
            offlineCacheHit(root, target);
            canonicalWorkspaceAliases(root);
            lazyModelOptions(root, target);
            lazyRecipe(root, target);
            offlineModels(root, target);
            downloadRecipe(root.toRealPath(), target.toRealPath());
            offlineRecipe(root.toRealPath(), target.toRealPath());
            nativePin(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    // Git marks object files read-only on Windows; this tree belongs only to this fixture.
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        DosFileAttributeView dos = Files.getFileAttributeView(path,
                                DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("fork-native smart-build contract checks passed: " + checks);
    }

    /** Runs the public entry point through the real CLI backend and a bounded synthetic process. */
    private static void mainRunsOneInference(Path root, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            System.out.println("POSIX main-entrypoint fixture not run on Windows; portable smart-build contracts still run");
            return;
        }
        Path count = root.resolve("main-invocations.txt");
        Path launcher = root.resolve("main-llama-fixture");
        Files.writeString(launcher, "#!/bin/sh\nprintf 'called\\n' >> '"
                + count.toString().replace("'", "'\"'\"'") + "'\nprintf 'single main candidate\\n'\n");
        check(launcher.toFile().setExecutable(true), "main fixture executable permission unavailable");
        Path model = root.resolve("main-model.gguf");
        Files.writeString(model, "synthetic fixture, not model data");
        Files.writeString(root.resolve("main.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=cli", "llamaBin=" + propertyPath(launcher),
                "model=" + propertyPath(model), "maxTokens=8", "timeoutSeconds=10") + "\n");
        SmartBuildMain.main(new String[] {"main.properties", root.toString(), target.toString()});
        check(Files.readAllLines(count).size() == 1, "one main invocation must run inference exactly once");
    }

    private static void offlineCacheHit(Path root, Path target) throws Exception {
        Path cache = Files.createDirectory(root.resolve("verified-offline-cache"));
        String modelBytes = "verified offline model fixture";
        String digest = hash(modelBytes);
        Path model = Files.writeString(cache.resolve(digest + ".gguf"), modelBytes);
        Files.writeString(root.resolve("offline-hit.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=cli", "llamaBin=bin/llama-cli",
                "modelUrl=https://example.invalid/model", "modelSha256=" + digest,
                "modelCacheDir=" + propertyPath(cache)) + "\n");
        AtomicInteger calls = new AtomicInteger();
        SmartBuildMain.ProviderFactory provider = (options, environment) -> {
            check(options.get("--model").equals(model.toRealPath().toString()), "offline cache hit did not select verified local bytes");
            check(!options.containsKey("--model-url") && !options.containsKey("--model-sha256")
                    && !options.containsKey("--model-cache-dir"), "offline cache hit retained download options");
            return new ModelBackend() {
                public String id() { return "llama.cpp-cli"; }
                public ModelResponse infer(ModelRequest request) {
                    calls.incrementAndGet();
                    return new ModelResponse("offline candidate", id(), hash(request.prompt()));
                }
            };
        };
        Path result = SmartBuildMain.run(Path.of("offline-hit.properties"), root, target, Map.of(), provider, true);
        check(calls.get() == 1 && Files.readString(result).equals("offline candidate"), "offline hit must infer once");
    }

    private static String propertyPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void offlineOutputRefusal(Path root) throws Exception {
        Path isolated = Files.createDirectory(root.resolve("blocked-output"));
        Path target = Files.createDirectory(isolated.resolve("target"));
        Files.writeString(isolated.resolve("prompt.txt"), "offline prompt");
        Files.writeString(target.resolve("smart-build"), "occupied");
        Files.writeString(isolated.resolve("recipe.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=cli", "modelUrl=https://example.invalid/model",
                "modelSha256=" + "0".repeat(64), "modelCacheDir=cache") + "\n");
        AtomicInteger opened = new AtomicInteger();
        SmartBuildMain.ProviderFactory forbidden = (options, environment) -> {
            opened.incrementAndGet();
            throw new AssertionError("invalid output opened a provider");
        };
        rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), isolated, target,
                Map.of(), forbidden, true));
        check(!Files.exists(isolated.resolve("cache")), "invalid output created a model cache before refusal");
        check(opened.get() == 0, "invalid output opened a provider");
    }

    private static void canonicalWorkspaceAliases(Path fixture) throws Exception {
        Path workspace = Files.createDirectories(fixture.resolve("canonical/standalone")).toRealPath();
        Path target = Files.createDirectory(workspace.resolve("target"));
        Files.writeString(workspace.resolve("prompt.txt"), "canonical alias prompt", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\n");
        AtomicInteger opens = new AtomicInteger();
        SmartBuildMain.ProviderFactory provider = (options, env) -> {
            opens.incrementAndGet();
            return new ModelBackend() {
                public String id() { return "llama.cpp-cli"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("alias candidate", id(), hash(request.prompt()));
                }
            };
        };
        Path foreignTarget = Files.createDirectories(fixture.resolve("foreign/target"));
        rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), workspace,
                foreignTarget, Map.of(), provider));
        check(opens.get() == 0, "foreign target performed provider work");

        Path ancestorAlias;
        try {
            ancestorAlias = Files.createSymbolicLink(fixture.resolve("workspace-alias"), workspace.getParent());
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            // Primary temporary-path/canonical-workspace admission and foreign-target
            // refusal always run. Only these manufactured aliases need link privileges.
            System.out.println("symbolic-link boundary fixtures unavailable: " + unavailable);
            return;
        }
        Path aliasedWorkspace = ancestorAlias.resolve("standalone");
        Path aliasedTarget = aliasedWorkspace.resolve("target");
        // Existing workspace ancestors may have another spelling, as with a Windows
        // short temp path or macOS /var. The final target and output directories may not.
        Path output = SmartBuildMain.run(Path.of("recipe.properties"), aliasedWorkspace,
                aliasedTarget, Map.of(), provider);
        check(output.startsWith(target.resolve("smart-build")), "aliased workspace output is not canonical");
        check(Files.readString(output).equals("alias candidate"), "aliased workspace changed output");
        Path repeated = SmartBuildMain.run(Path.of("recipe.properties"), workspace,
                aliasedTarget, Map.of(), provider);
        check(output.equals(repeated) && opens.get() == 2, "target ancestor alias was not admitted consistently");

        Path movedTarget = Files.move(target, workspace.resolve("target-actual"));
        Files.createSymbolicLink(target, movedTarget);
        rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), aliasedWorkspace,
                aliasedTarget, Map.of(), provider));
        check(opens.get() == 2, "target symlink performed provider work");
        Files.delete(target);
        Files.move(movedTarget, target);

        Path outputRoot = target.resolve("smart-build");
        Path movedOutput = Files.move(outputRoot, target.resolve("output-actual"));
        Files.createSymbolicLink(outputRoot, movedOutput);
        rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), workspace,
                aliasedTarget, Map.of(), provider));
        check(opens.get() == 2, "output symlink performed provider work");
        Files.delete(outputRoot);
        Files.move(movedOutput, outputRoot);

    }

    private static void lazyModelOptions(Path root, Path target) throws Exception {
        Path cache = root.resolve("lazy-options-cache");
        Files.writeString(root.resolve("lazy.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=jni", "jniLib=/explicit/chromellm-jni",
                "modelUrl=https://github.com/hsoliwal/ChromeLLM/releases/download/pinned/weights.bin",
                "modelSha256=" + "a".repeat(64), "modelCacheDir=" + cache.toString().replace("\\", "\\\\"),
                "computeBackend=cpu", "gpuLayers=0", "maxTokens=8", "timeoutSeconds=10") + "\n");
        AtomicInteger calls = new AtomicInteger();
        SmartBuildMain.ProviderFactory lazy = (options, environment) -> {
            check(options.get("--backend").equals("jni"), "JNI provider selection lost");
            check(options.get("--jni-lib").equals(root.resolve("/explicit/chromellm-jni").normalize().toString()),
                    "JNI library lost");
            check(options.get("--model-url").endsWith("/pinned/weights.bin"), "model URL lost");
            check(options.get("--model-sha256").equals("a".repeat(64)), "model digest lost");
            check(Path.of(options.get("--model-cache-dir")).equals(cache), "model cache lost");
            check(!options.containsKey("--model"), "lazy model replaced with a local path");
            check(options.get("--compute-backend").equals("cpu") && options.get("--gpu-layers").equals("0"),
                    "explicit CPU policy lost");
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    calls.incrementAndGet();
                    return new ModelResponse("lazy candidate", id(), hash(request.prompt()));
                }
            };
        };
        Path candidate = SmartBuildMain.run(Path.of("lazy.properties"), root, target, Map.of(), lazy);
        check(calls.get() == 1 && Files.readString(candidate).equals("lazy candidate"),
                "lazy model recipe was not executed");
        Files.writeString(root.resolve("lazy-relative.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=jni", "jniLib=lib/chromellm-jni",
                "modelUrl=https://github.com/hsoliwal/ChromeLLM/releases/download/pinned/weights.bin",
                "modelSha256=" + "a".repeat(64), "modelCacheDir=model-options-cache",
                "computeBackend=cpu", "gpuLayers=0") + "\n");
        SmartBuildMain.ProviderFactory relative = (options, environment) -> {
            check(options.get("--jni-lib").equals(root.resolve("lib/chromellm-jni").toString()),
                    "relative JNI library lost");
            check(options.get("--model-cache-dir").equals(root.resolve("model-options-cache").toString()),
                    "relative model cache lost");
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("relative lazy candidate", id(), hash(request.prompt()));
                }
            };
        };
        check(Files.readString(SmartBuildMain.run(Path.of("lazy-relative.properties"), root, target,
                Map.of(), relative)).equals("relative lazy candidate"), "relative lazy recipe was not executed");
    }

    private static void lazyRecipe(Path root, Path target) throws Exception {
        Path cache = Files.createDirectory(root.resolve("model-cache"));
        String digest = "a".repeat(64);
        Files.writeString(root.resolve("lazy.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=jni", "jniLib=/explicit/missing-library",
                "modelUrl=https://example.invalid/licensed.gguf", "modelSha256=" + digest,
                "modelCacheDir=" + cache.toString().replace("\\", "\\\\"),
                "computeBackend=cpu", "gpuLayers=0") + "\n");
        SmartBuildMain.ProviderFactory selection = (options, environment) -> {
            check(options.get("--model-url").equals("https://example.invalid/licensed.gguf"),
                    "lazy model URL selection lost");
            check(options.get("--model-sha256").equals(digest), "lazy digest selection lost");
            check(Path.of(options.get("--model-cache-dir")).equals(cache), "lazy cache selection lost");
            check(!options.containsKey("--model"), "lazy model gained an implicit local path");
            try (ModelBackend deferred = StandaloneMain.backend(options, environment)) {
                check(deferred instanceof LazyModelBackend, "existing lazy provider was not selected");
                check(((LazyModelBackend) deferred).progress().state() == ModelArtifactCache.State.UNPREPARED,
                        "provider selection initialized a model eagerly");
            }
            try (var files = Files.list(cache)) {
                check(files.findAny().isEmpty(), "provider selection wrote model artifacts");
            }
            // This Java-only selection fixture does not pretend to execute a native model.
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("lazy selection fixture", id(), hash(request.prompt()));
                }
            };
        };
        Path output = SmartBuildMain.run(Path.of("lazy.properties"), root, target, Map.of(), selection);
        check(Files.readString(output).equals("lazy selection fixture"), "lazy recipe result changed");
    }

    private static void offlineModels(Path root, Path target) throws Exception {
        Path cache = Files.createDirectories(root.resolve("model-cache"));
        String bytes = "offline cache fixture, not a real model";
        String digest = hash(bytes);
        Path model = cache.resolve(digest + ".gguf");
        Files.writeString(model, bytes);
        String recipeText = String.join("\n", "promptFile=prompt.txt", "backend=cli",
                "llamaBin=/explicit/llama-cli", "modelUrl=https://example.invalid/model.bin",
                "modelSha256=" + digest,
                "modelCacheDir=" + cache.toString().replace("\\", "\\\\"),
                "maxTokens=8", "timeoutSeconds=10") + "\n";
        Files.writeString(root.resolve("remote.properties"), recipeText);
        AtomicInteger providers = new AtomicInteger();
        SmartBuildMain.ProviderFactory offlineProvider = (options, ignored) -> {
            providers.incrementAndGet();
            check(options.get("--backend").equals("cli")
                            && options.get("--llama-bin").equals(root.resolve("/explicit/llama-cli").normalize().toString()),
                    "offline model changed provider selection");
            check(Path.of(options.get("--model")).equals(model.toAbsolutePath()),
                    "offline model path was not the pinned cache entry");
            check(!options.containsKey("--model-url") && !options.containsKey("--model-sha256")
                            && !options.containsKey("--model-cache-dir"),
                    "offline provider retained remote acquisition options");
            return fixtureBackend();
        };
        Path output = SmartBuildMain.run(Path.of("remote.properties"), root, target,
                Map.of(), offlineProvider, true);
        check(Files.readString(output).equals("offline-candidate") && providers.get() == 1,
                "verified offline model was not passed to provider");
        Path argvOutput = SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "true"}, Map.of(), offlineProvider);
        check(output.equals(argvOutput) && providers.get() == 2, "four-argument offline mode lost cache reuse");

        SmartBuildMain.ProviderFactory onlineProvider = (options, ignored) -> {
            providers.incrementAndGet();
            check(options.get("--model-url").equals("https://example.invalid/model.bin")
                            && options.get("--model-sha256").equals(digest)
                            && !options.containsKey("--model"),
                    "default smart-build changed lazy model acquisition options");
            return fixtureBackend();
        };
        check(output.equals(SmartBuildMain.run(Path.of("remote.properties"), root, target,
                Map.of(), onlineProvider)), "original run signature lost default behavior");
        check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString()}, Map.of(), onlineProvider)), "three-argument invocation lost default behavior");
        check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "false"}, Map.of(), onlineProvider)), "explicit online flag changed behavior");

        check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "--offline=true"}, Map.of(), offlineProvider)),
                "Maven offline argument lost verified cache reuse");
        check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "--offline=false"}, Map.of(), onlineProvider)),
                "Maven online argument changed lazy acquisition");

        String previousOffline = System.getProperty("chromellm.offline");
        try {
            for (String property : new String[] {"true", "offline=true"}) {
                System.setProperty("chromellm.offline", property);
                check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                        target.toString()}, Map.of(), offlineProvider)), "legacy offline system property lost cache reuse");
                check(output.equals(SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                        target.toString(), "false"}, Map.of(), onlineProvider)), "explicit offline argument lost precedence");
            }
        } finally {
            if (previousOffline == null) System.clearProperty("chromellm.offline");
            else System.setProperty("chromellm.offline", previousOffline);
        }

        int opened = providers.get();
        for (String invalid : new String[] {"TRUE", "yes", "", "${settings.offline}",
                "--offline=", "--offline=TRUE", "--offline=--offline=false"}) {
            rejectIllegal(() -> SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                    target.toString(), invalid}, Map.of(), offlineProvider));
        }
        rejectIllegal(() -> SmartBuildMain.run(new String[] {"remote.properties", root.toString()},
                Map.of(), offlineProvider));
        rejectIllegal(() -> SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "false", "extra"}, Map.of(), offlineProvider));
        rejectIo(() -> SmartBuildMain.run(Path.of("remote.properties"), root, root.resolve("wrong-target"),
                Map.of(), onlineProvider));
        check(providers.get() == opened, "invalid arguments or target directory opened provider");

        for (String invalidOptions : new String[] {"model=/explicit/conflicting.gguf\n",
                "jniLib=/explicit/foreign-provider.so\n", "computeBackend=cpu\n"}) {
            Files.writeString(root.resolve("remote.properties"), recipeText + invalidOptions);
            rejectIllegal(() -> SmartBuildMain.run(Path.of("remote.properties"), root, target,
                    Map.of(), offlineProvider, true));
        }
        check(providers.get() == opened, "offline substitution bypassed provider admission");
        Files.writeString(root.resolve("remote.properties"), recipeText);

        Files.writeString(model, "corrupt cached bytes");
        rejectIo(() -> SmartBuildMain.run(Path.of("remote.properties"), root, target,
                Map.of(), offlineProvider, true));
        check(providers.get() == opened && Files.readString(model).equals("corrupt cached bytes"),
                "offline corrupt cache was repaired or passed to provider");
        Files.delete(model);
        rejectIo(() -> SmartBuildMain.run(Path.of("remote.properties"), root, target,
                Map.of(), offlineProvider, true));
        try (var entries = Files.list(cache)) {
            check(entries.findAny().isEmpty() && providers.get() == opened,
                    "offline miss downloaded, wrote staging state, or opened provider");
        }
        Files.delete(cache);
        rejectIo(() -> SmartBuildMain.run(new String[] {"remote.properties", root.toString(),
                target.toString(), "true"}, Map.of(), offlineProvider));
        check(!Files.exists(cache) && providers.get() == opened, "offline mode created a missing cache directory");
    }

    private static ModelBackend fixtureBackend() {
        return new ModelBackend() {
            public String id() { return "llama.cpp-cli"; }
            public ModelResponse infer(ModelRequest request) {
                return new ModelResponse("offline-candidate", id(), hash(request.prompt()));
            }
        };
    }

    private static void downloadRecipe(Path root, Path target) throws Exception {
        Files.writeString(root.resolve("recipe.properties"), String.join("\n", "promptFile=prompt.txt",
                "backend=jni", "modelUrl=https://example.invalid/model.gguf", "modelSha256=" + "a".repeat(64),
                "modelCacheDir=target/model-cache", "modelFollowRedirects=true",
                "modelDownloadTimeoutSeconds=120", "modelMaxBytes=2097152") + "\n");
        AtomicInteger opened = new AtomicInteger();
        SmartBuildMain.ProviderFactory provider = (options, ignored) -> {
            opened.incrementAndGet();
            check(options.get("--model-cache-dir").equals(target.resolve("model-cache").toString()),
                    "relative cache did not resolve against standalone root");
            check(!Files.exists(target.resolve("model-cache")), "managed cache created before provider validation");
            check(options.get("--model-follow-redirects").equals("true")
                    && options.get("--model-download-timeout-seconds").equals("120")
                    && options.get("--model-max-bytes").equals("2097152"), "download policy options lost");
            check(!options.containsKey("--jni-lib"), "packaged provider must not require an explicit JNI path");
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    check(Files.isDirectory(target.resolve("model-cache")), "managed cache missing at first inference");
                    return new ModelResponse("download recipe candidate", id(), hash(request.prompt()));
                }
            };
        };
        SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider);
        check(opened.get() == 1, "one explicit provider expected");
        rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, root.resolve("other-target"), Map.of(), provider));
        check(opened.get() == 1, "invalid Maven target reached model initialization");
    }

    private static void offlineRecipe(Path root, Path target) throws Exception {
        Path cache = Files.createDirectory(root.resolve("offline-existing"));
        byte[] fixture = "verified offline model".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture));
        Path model = Files.write(cache.resolve(digest + ".gguf"), fixture);
        Files.writeString(root.resolve("offline.properties"), String.join("\n", "promptFile=prompt.txt",
                "backend=jni", "modelUrl=https://offline.invalid/model", "modelSha256=" + digest,
                "modelCacheDir=offline-existing", "modelFollowRedirects=true",
                "modelDownloadTimeoutSeconds=5", "modelMaxBytes=64") + "\n");
        AtomicInteger opens = new AtomicInteger();
        SmartBuildMain.ProviderFactory provider = (options, environment) -> {
            opens.incrementAndGet();
            check(Path.of(options.get("--model")).equals(model), "offline model path was not verified");
            check(options.keySet().stream().noneMatch(key -> key.equals("--model-url")
                    || key.equals("--model-sha256") || key.equals("--model-cache-dir")
                    || key.equals("--model-follow-redirects") || key.equals("--model-download-timeout-seconds")
                    || key.equals("--model-max-bytes")), "offline local provider retained acquisition-only options");
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("offline candidate", id(), hash(request.prompt()));
                }
            };
        };
        SmartBuildMain.run(Path.of("offline.properties"), root, target, Map.of(), provider, true);
        check(opens.get() == 1, "offline recipe opened more than one provider");
        Files.write(model, new byte[fixture.length]);
        rejectIo(() -> SmartBuildMain.run(Path.of("offline.properties"), root, target, Map.of(), provider, true));
        check(opens.get() == 1, "corrupt offline model initialized the provider");
    }

    private static void nativePin(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("fork"));
        Files.createDirectories(source.resolve("include"));
        Files.createDirectories(source.resolve("ggml/include"));
        Files.writeString(source.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\n");
        Files.writeString(source.resolve("include/llama.h"), "// stub\n");
        Files.writeString(source.resolve("ggml/include/ggml-backend.h"), "// stub\n");
        git(source, "init", "--quiet");
        git(source, "config", "user.name", "Fixture");
        git(source, "config", "user.email", "fixture@example.invalid");
        git(source, "add", "CMakeLists.txt", "include/llama.h", "ggml/include/ggml-backend.h");
        git(source, "commit", "--quiet", "-m", "fixture");
        String sha = git(source, "rev-parse", "HEAD").strip();
        ForkSourcePin.verify(source, sha);
        check(true, "verified source pin");
        rejectIo(() -> ForkSourcePin.verify(source, "0".repeat(40)));
        Files.writeString(source.resolve("include/llama.h"), "// changed\n");
        rejectIo(() -> ForkSourcePin.verify(source, sha));
        rejectIllegal(() -> ForkSourcePin.verify(source, "not-a-sha"));
        git(source, "restore", "include/llama.h");
        ForkSourcePin.verify(source, sha);
        Files.writeString(source.resolve("untracked.c"), "// untracked\n");
        rejectIo(() -> ForkSourcePin.verify(source, sha));
    }
    private static String git(Path root, String... args) throws Exception {
        java.util.ArrayList<String> commands = new java.util.ArrayList<>(java.util.List.of("git", "-C", root.toString()));
        commands.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(commands).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new AssertionError("fixture git failed: " + new String(output));
        return new String(output, StandardCharsets.UTF_8);
    }
    private static String hash(String prompt) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void rejectIo(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected IO failure"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejectIllegal(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected configuration rejection"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean accepted, String message) {
        checks++;
        if (!accepted) throw new AssertionError(message);
    }
}
