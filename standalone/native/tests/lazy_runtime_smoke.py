#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run the real Maven/JNI path with a cold HTTPS cache, then with no artifact server.

Python owns only the HTTPS fixture and process assertions. Java's production
resolver downloads/verifies the native library and the published GGUF model.
"""

import argparse
import hashlib
import http.server
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import urllib.request


MODEL_SHA = "8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684"
MODEL_BYTES = 105454144
RELEASE = "https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/"
MODEL_URL = RELEASE + "weights.bin"
PROMPT = "<|im_start|>user\nWrite one short sentence about an oak tree.<|im_end|>\n<|im_start|>assistant\n"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line)


def write_properties(path, values):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
    path.write_text("".join(key + "=" + escape(value) + "\n" for key, value in values.items()), encoding="utf-8")


def run(command, project, environment, log, expected_failure=None):
    print("Running " + log.name, flush=True)
    with log.open("w", encoding="utf-8") as output:
        completed = subprocess.run(command, cwd=project, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT, timeout=600)
    text = log.read_text(encoding="utf-8", errors="replace")
    if "WARNING in native method" in text or "FATAL ERROR in native method" in text:
        raise AssertionError("JNI checker reported an error; see " + str(log))
    if expected_failure is None:
        if completed.returncode:
            raise AssertionError("Command failed (exit " + str(completed.returncode) + "): " + str(log) + "\n" + text[-12000:])
    elif completed.returncode == 0 or expected_failure.lower() not in text.lower():
        raise AssertionError("Expected refusal containing " + expected_failure + " (exit " + str(completed.returncode) + "): " + str(log) + "\n" + text[-8000:])
    return text


def candidate(project, recipe):
    recipe_hash = sha(recipe)
    for receipt in (project / "target/smart-build").glob("receipt-*.properties"):
        recorded = properties(receipt)
        if recorded.get("recipe.sha256") != recipe_hash:
            continue
        output = receipt.with_name(receipt.name.replace("receipt-", "candidate-").replace(".properties", ".txt"))
        text = output.read_text(encoding="utf-8")
        assert text.strip() and 0 < output.stat().st_size <= 4 * 1024 * 1024, "missing real output"
        assert recorded["backend"] == "llama.cpp-jni", "wrong provider"
        assert recorded["prompt.sha256"] == hashlib.sha256(PROMPT.encode()).hexdigest(), "prompt receipt"
        assert recorded["candidate.sha256"] == sha(output), "output receipt"
        return text
    raise AssertionError("No candidate receipt for " + str(recipe))


def cache_file(cache, expected_sha, expected_bytes):
    files = [path for path in cache.rglob("*") if path.is_file() and path.name.startswith(expected_sha)]
    matching = [path for path in files if path.stat().st_size == expected_bytes and sha(path) == expected_sha]
    assert matching, "no verified cache artifact for " + expected_sha
    return matching[0]


def outputs(project):
    return {path.name: sha(path) for path in (project / "target/smart-build").glob("*") if path.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--mvn", default="mvn.cmd" if os.name == "nt" else "mvn")
    parser.add_argument("--java", default="java")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--model-local", type=Path,
                        help="serve these exact released model bytes locally for a network-restricted qualification")
    args = parser.parse_args()
    resolved_maven = shutil.which(args.mvn)
    if resolved_maven is None:
        parser.error("Maven executable was not found: " + args.mvn)
    args.mvn = resolved_maven
    project = args.project.resolve(strict=True)
    package = args.package.resolve(strict=True)
    metadata = properties(package / "runtime.properties")
    library = package / metadata["file"]
    native_sha, native_bytes = metadata["sha256"], int(metadata["bytes"])
    assert library.stat().st_size == native_bytes and sha(library) == native_sha, "package identity"
    assert metadata["compute"] == "cpu", "CPU artifact required"
    if args.model_local:
        args.model_local = args.model_local.resolve(strict=True)
        assert args.model_local.stat().st_size == MODEL_BYTES and sha(args.model_local) == MODEL_SHA

    work = Path(tempfile.mkdtemp(prefix="lazy-runtime-", dir=project / "target"))
    native_cache = work / "native-cache"
    model_cache = work / "model-cache"
    native_cache.mkdir()
    model_cache.mkdir()
    prompt = work / "prompt.txt"
    prompt.write_text(PROMPT, encoding="utf-8")

    certificate, key = work / "server.crt", work / "server.key"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-keyout", str(key), "-out", str(certificate), "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    java_home = Path(os.environ.get("JAVA_HOME") or Path(shutil.which(args.java)).resolve().parent.parent)
    truststore = work / "truststore"
    system_roots = Path("/etc/ssl/certs/java/cacerts")
    shutil.copyfile(system_roots if system_roots.is_file() else java_home / "lib/security/cacerts", truststore)
    subprocess.run([args.keytool, "-importcert", "-noprompt", "-alias", "chromellm-fixture",
                    "-file", str(certificate), "-keystore", str(truststore), "-storepass", "changeit"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    environment = os.environ.copy()
    # Start each Maven/Java gate in a fresh JVM; preserve normal public HTTPS roots.
    # HotSpot reads _JAVA_OPTIONS after command-line VM flags. This keeps the
    # fixture truststore effective even with a Maven wrapper's normal CA setting.
    environment["_JAVA_OPTIONS"] = (environment.get("_JAVA_OPTIONS", "")
        + ' -Xcheck:jni -Djavax.net.ssl.trustStore="' + str(truststore)
        + '" -Djavax.net.ssl.trustStorePassword=changeit')
    transferred = {"native": 0, "model": 0}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/native":
                self.send_response(302)
                self.send_header("Location", "/runtime.bin")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path == "/runtime.bin":
                path, kind = library, "native"
            elif self.path == "/model.gguf" and args.model_local:
                path, kind = args.model_local, "model"
            else:
                self.send_error(404)
                return
            transferred[kind] += 1
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(path.stat().st_size))
            self.end_headers()
            with path.open("rb") as stream:
                shutil.copyfileobj(stream, self.wfile, 64 * 1024)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certificate, key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    origin = "https://127.0.0.1:" + str(server.server_port)
    values = {
        "promptFile": prompt.relative_to(project).as_posix(), "backend": "jni",
        "jniUrl": origin + "/native", "jniSha256": native_sha, "jniBytes": native_bytes,
        "jniCacheDir": native_cache, "modelUrl": origin + "/model.gguf" if args.model_local else MODEL_URL,
        "modelSha256": MODEL_SHA, "modelBytes": MODEL_BYTES, "modelCacheDir": model_cache,
        "computeBackend": "cpu", "gpuLayers": "0", "contextTokens": "512", "threads": "2",
        "maxTokens": "48", "timeoutSeconds": "120",
    }

    def maven(recipe, offline=False):
        return [args.mvn, *( ["-o"] if offline else []), "-B", "-ntp", "-f", str(project / "pom.xml"),
                "-Psmart-build", "-Dchromellm.smart.recipe=" + recipe.relative_to(project).as_posix(), "verify"]

    cold = work / "cold.properties"
    write_properties(cold, values)
    try:
        run(maven(cold), project, environment, work / "cold.log")
        assert transferred["native"] == 1, "cold Maven run must fetch native binary exactly once"
        if args.model_local:
            assert transferred["model"] == 1, "cold Maven run must fetch model exactly once"
    finally:
        server.shutdown()
        server.server_close()
        server_thread.join(timeout=5)
    first = candidate(project, cold)
    native = cache_file(native_cache, native_sha, native_bytes)
    model = cache_file(model_cache, MODEL_SHA, MODEL_BYTES)
    native_mtime, model_mtime = native.stat().st_mtime_ns, model.stat().st_mtime_ns

    # Both URLs now point to a closed listener. A network attempt is a test failure.
    values["modelUrl"] = origin + "/offline-model.gguf"
    warm = work / "warm.properties"
    write_properties(warm, values)
    run(maven(warm, True), project, environment, work / "warm-offline.log")
    assert candidate(project, warm) == first, "fresh offline JVM must produce the same greedy CPU result"
    assert native.stat().st_mtime_ns == native_mtime and model.stat().st_mtime_ns == model_mtime, "warm cache was rewritten"
    assert sha(native) == native_sha and sha(model) == MODEL_SHA, "warm cache bytes changed"

    # Refuse a valid model with an absent or corrupted native artifact before loading it.
    before = outputs(project)
    empty = work / "empty-native-cache"
    empty.mkdir()
    missing = work / "missing.properties"
    write_properties(missing, dict(values, jniCacheDir=empty))
    run(maven(missing, True), project, environment, work / "missing-offline.log", "offline")
    assert outputs(project) == before, "offline native miss published output"
    with native.open("r+b") as stream:
        stream.seek(-1, os.SEEK_END)
        original = stream.read(1)
        stream.seek(-1, os.SEEK_END)
        stream.write(bytes([original[0] ^ 1]))
    try:
        run(maven(warm, True), project, environment, work / "corrupt-native.log", "SHA-256")
        assert outputs(project) == before, "corrupt native cache published output"
        assert sha(native) != native_sha, "corruption was silently repaired"
    finally:
        with native.open("r+b") as stream:
            stream.seek(-1, os.SEEK_END)
            stream.write(original)
    assert sha(native) == native_sha

    # Refuse a changed model before JNI loading, retaining the legacy corruption gate.
    with model.open("r+b") as stream:
        stream.seek(-1, os.SEEK_END)
        original_model = stream.read(1)
        stream.seek(-1, os.SEEK_END)
        stream.write(bytes([original_model[0] ^ 1]))
    try:
        run(maven(warm, True), project, environment, work / "corrupt-model.log", "SHA-256")
        assert outputs(project) == before, "corrupt model cache published output"
        assert sha(model) != MODEL_SHA, "model corruption was silently repaired"
    finally:
        with model.open("r+b") as stream:
            stream.seek(-1, os.SEEK_END)
            stream.write(original_model)
    assert sha(model) == MODEL_SHA

    # Keep the established real-model deterministic-repeat, receipts and native close
    # checks through the explicit six-argument real-model test entrypoint.
    classpath = os.pathsep.join(str(project / "target" / name) for name in ("classes", "test-classes"))
    run([args.java, "-cp", classpath, "io.synexia.chromellm.standalone.RealGgufContractTestMain",
         str(native), origin + "/offline-model.gguf", MODEL_SHA, str(model_cache),
         str(MODEL_BYTES), "--offline=true"],
        project, environment, work / "real-model-contract.log")

    write_properties(work / "result.properties", {
        "result": "PASS", "native.sha256": native_sha, "native.bytes": native_bytes,
        "model.sha256": MODEL_SHA, "model.bytes": MODEL_BYTES,
        "native.downloads": transferred["native"], "native.redirect": "https-relative",
        "model.source": "verified-local-release-bytes" if args.model_local else MODEL_URL,
        "cold.inference": "pass", "warm.offline.inference": "pass", "native.corruption": "rejected",
        "offline.native.miss": "rejected", "model.corruption": "rejected", "real.model.lifecycle": "pass",
    })
    for source_name, target_name, digest in (
        ("LICENSE", "LICENSE.model.txt", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        ("NOTICE.txt", "NOTICE.model.txt", "a37ead7a580a7b000946ccab0495915e8bc78703a96e10dfbc53392b60996ea2"),
    ):
        with urllib.request.urlopen(RELEASE + source_name, timeout=30) as response:
            contents = response.read(1024 * 1024)
        assert hashlib.sha256(contents).hexdigest() == digest, "model license/notice identity"
        (package / target_name).write_bytes(contents)
    write_properties(package / "model.properties", {
        "modelUrl": MODEL_URL, "modelSha256": MODEL_SHA, "modelBytes": MODEL_BYTES,
        "license": "Apache-2.0", "licenseUrl": RELEASE + "LICENSE", "noticeUrl": RELEASE + "NOTICE.txt",
    })
    # TLS private keys are test fixtures, never part of the uploaded runtime/log bundle.
    key.unlink()
    truststore.unlink()
    print("PASS: real Maven lazy JNI HTTPS download, warm offline inference, corruption refusal and lifecycle", flush=True)
    print("Results: " + str(work), flush=True)


if __name__ == "__main__":
    main()
