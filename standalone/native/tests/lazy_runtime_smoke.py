#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Artifact-consumer additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
"""Run the real Maven/JNI path with a cold HTTPS cache, then with no artifact server.

Python owns only the HTTPS fixture and process assertions. Java's production
resolver downloads/verifies the native library and the published GGUF model.
The default producer mode builds the checked-out project. Artifact-consumer mode
installs the verified downloaded JAR into an isolated Maven repository, executes
a source-free consumer, and checks the origin of its loaded production classes.
"""

import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import platform
import re
import shutil
import ssl
import subprocess
import tempfile
import threading
import urllib.parse
import urllib.request
import zipfile


MODEL_SHA = "8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684"
MODEL_BYTES = 105454144
RELEASE = "https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/"
MODEL_URL = RELEASE + "weights.bin"
PROMPT = "<|im_start|>user\nWrite one short sentence about an oak tree.<|im_end|>\n<|im_start|>assistant\n"
JAVA_PACKAGE = "io.synexia.chromellm.standalone."
REAL_MODEL_TEST = JAVA_PACKAGE + "RealGgufContractTestMain"
REAL_MODEL_SOURCE = "src/test/java/io/synexia/chromellm/standalone/RealGgufContractTestMain.java"
NATIVE_SOURCES = {
    "bridge.sha256": "native/llama_jni.cpp",
    "native.build.sha256": "native/CMakeLists.txt",
    "native.package.sha256": "native/package_runtime.cmake",
}
CLASS_TRACE_LIMIT = 8 * 1024 * 1024
CLASS_TRACE_PROCESSES = 8
EXEC_GOAL = "org.codehaus.mojo:exec-maven-plugin:3.5.0:java@run-explicit-smart-build-recipe"
# This is the install plugin already selected by Maven 3.9.11's lifecycle bindings.
INSTALL_GOAL = "org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line)


def strict_properties(contents, description):
    """Read the flat package identity format; ambiguous keys are not admissible."""
    if len(contents) > 64 * 1024:
        raise AssertionError(description + " exceeds the metadata bound")
    result = {}
    for line in contents.decode("utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise AssertionError("Malformed " + description)
        key, value = line.split("=", 1)
        if not key or key != key.strip() or key in result or not value or value != value.strip():
            raise AssertionError("Ambiguous " + description + " key: " + key)
        result[key] = value
    return result


def require_digest(value, description, length=64):
    if not value or re.fullmatch("[0-9a-f]{" + str(length) + "}", value) is None:
        raise AssertionError("Invalid " + description)
    return value


def positive_bytes(value, description):
    if re.fullmatch(r"[1-9][0-9]*", str(value)) is None:
        raise AssertionError("Invalid " + description)
    return int(value)


def package_file(package, name):
    if not name or name in (".", "..") or "/" in name or "\\" in name or ":" in name:
        raise AssertionError("Package entry must be a flat filename")
    path = package / name
    if path.is_symlink() or not path.is_file():
        raise AssertionError("Package entry must be a regular file: " + name)
    return path


def verify_file(path, digest, length, description):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != length or sha(path) != digest:
        raise AssertionError(description + " identity mismatch")


def platform_name(system, machine):
    system, machine = system.lower(), machine.lower()
    arch = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(machine)
    name = {("linux", "x86_64"): "linux-x86_64", ("windows", "x86_64"): "windows-x86_64",
            ("darwin", "aarch64"): "macos-aarch64"}.get((system, arch))
    if name is None:
        raise AssertionError("Unsupported runtime platform: " + system + "/" + machine)
    return name


def verify_checkout(project, expected_commit):
    """Bind the inspected native owners and compiled test closure to the Git commit."""
    paths = [*NATIVE_SOURCES.values(), REAL_MODEL_SOURCE]
    command = ["git", "-C", str(project), "rev-parse", "HEAD", *["HEAD:./" + path for path in paths]]
    result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=30)
    identities = result.stdout.splitlines()
    if len(identities) != len(paths) + 1 or identities[0] != expected_commit:
        raise AssertionError("Checked-out source commit does not match --expected-commit")
    for relative, expected_blob in zip(paths, identities[1:]):
        path = project / relative
        if path.is_symlink() or not path.is_file():
            raise AssertionError("Missing checked-out source: " + relative)
        contents = path.read_bytes()
        actual_blob = hashlib.sha1(b"blob " + str(len(contents)).encode("ascii") + b"\0" + contents).hexdigest()
        if actual_blob != expected_blob:
            raise AssertionError("Checked-out source has changed: " + relative)


def validate_consumer_package(project, package, expected_commit, expected_platform=None,
                              expected_java_bytes=None, expected_java_sha256=None, environment=None):
    """Validate all artifact and source inputs before Maven, TLS, or model acquisition."""
    require_digest(expected_commit, "expected ChromeLLM commit", 40)
    metadata_file = package_file(package, "runtime.properties")
    metadata = strict_properties(metadata_file.read_bytes(), "runtime.properties")
    if metadata.get("schema") != "1" or metadata.get("compute") != "cpu":
        raise AssertionError("Schema 1 CPU artifact required")
    if metadata.get("chromellm.commit") != expected_commit:
        raise AssertionError("Package source commit does not match --expected-commit")
    environment = os.environ if environment is None else environment
    if environment.get("GITHUB_SHA") and environment["GITHUB_SHA"] != expected_commit:
        raise AssertionError("Workflow source commit does not match --expected-commit")
    require_digest(metadata.get("llama.commit"), "llama.cpp commit", 40)
    for variable in ("NATIVE_SOURCE_COMMIT", "LLAMA_COMMIT"):
        if environment.get(variable) and environment[variable] != metadata["llama.commit"]:
            raise AssertionError(variable + " does not match the native source commit")
    host = platform_name(platform.system(), platform.machine())
    if expected_platform is not None and expected_platform != host:
        raise AssertionError("Expected platform does not match this host")
    if metadata.get("platform") != host or platform_name(metadata.get("os", ""), metadata.get("arch", "")) != host:
        raise AssertionError("Package platform does not match this host")
    native_sha = require_digest(metadata.get("sha256"), "native SHA-256")
    native_bytes = positive_bytes(metadata.get("bytes"), "native byte length")
    verify_file(package_file(package, metadata.get("file")), native_sha, native_bytes, "Native artifact")
    java_sha = require_digest(metadata.get("java.sha256"), "Java SHA-256")
    if expected_java_sha256 is not None and require_digest(expected_java_sha256, "expected Java SHA-256") != java_sha:
        raise AssertionError("Expected Java SHA-256 disagrees with the manifest")
    if "java.bytes" not in metadata:
        # Historical bundles predate this field. Both independent caller pins are
        # required; never silently infer a missing identity from the downloaded file.
        if expected_java_bytes is None or expected_java_sha256 is None:
            raise AssertionError("Missing java.bytes requires --expected-java-bytes and --expected-java-sha256")
        metadata["java.bytes"] = str(positive_bytes(expected_java_bytes, "expected Java byte length"))
        metadata["java.bytes.authority"] = "explicit-historical-caller-pin"
    else:
        metadata["java.bytes.authority"] = "runtime.properties"
    java_bytes = positive_bytes(metadata["java.bytes"], "Java byte length")
    if expected_java_bytes is not None and positive_bytes(expected_java_bytes, "expected Java byte length") != java_bytes:
        raise AssertionError("Expected Java byte length disagrees with the manifest")
    jar = package_file(package, metadata.get("java.file"))
    verify_file(jar, java_sha, java_bytes, "Java artifact")
    coordinates_path = "META-INF/maven/io.synexia.chromellm/chromellm-standalone/pom.properties"
    with zipfile.ZipFile(jar) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == coordinates_path]
        if len(entries) != 1 or entries[0].file_size > 64 * 1024:
            raise AssertionError("Missing or ambiguous embedded Maven coordinates")
        coordinates = strict_properties(archive.read(entries[0]), "embedded Maven coordinates")
    if coordinates.get("groupId") != "io.synexia.chromellm" or coordinates.get("artifactId") != "chromellm-standalone":
        raise AssertionError("Unexpected Java artifact coordinates")
    version = coordinates.get("version", "")
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.+-]*", version) is None:
        raise AssertionError("Invalid embedded Maven version")
    metadata["java.maven.version"] = version
    for key, relative in NATIVE_SOURCES.items():
        expected = require_digest(metadata.get(key), key)
        if sha(project / relative) != expected:
            raise AssertionError("Native source identity mismatch: " + relative)
    verify_checkout(project, expected_commit)
    metadata["real.model.test.sha256"] = sha(project / REAL_MODEL_SOURCE)
    return metadata


def consumer_pom(version):
    """A dependency-only receiving project: no source lifecycle or output classpath."""
    return """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.synexia.chromellm.tests</groupId><artifactId>downloaded-runtime-consumer</artifactId><version>1</version>
  <packaging>pom</packaging>
  <dependencies><dependency>
    <groupId>io.synexia.chromellm</groupId><artifactId>chromellm-standalone</artifactId><version>@@VERSION@@</version>
  </dependency></dependencies>
  <build><plugins><plugin>
    <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version>
    <executions><execution><id>run-explicit-smart-build-recipe</id><goals><goal>java</goal></goals><configuration>
      <mainClass>io.synexia.chromellm.standalone.SmartBuildMain</mainClass>
      <addOutputToClasspath>false</addOutputToClasspath><addResourcesToClasspath>false</addResourcesToClasspath>
      <includeProjectDependencies>true</includeProjectDependencies><includePluginDependencies>false</includePluginDependencies>
      <classpathScope>runtime</classpathScope>
      <arguments>
        <argument>${chromellm.smart.recipe}</argument><argument>${project.basedir}</argument>
        <argument>${project.build.directory}</argument><argument>--offline=${settings.offline}</argument>
      </arguments>
    </configuration></execution></executions>
  </plugin></plugins></build>
</project>
""".replace("@@VERSION@@", version)


def prepare_consumer(project, repository, version):
    project.mkdir()
    # SmartBuildMain admits only an existing in-project target directory. The
    # consumer has no lifecycle build that could create it as a side effect.
    (project / "target").mkdir()
    repository.mkdir()
    (project / "pom.xml").write_text(consumer_pom(version), encoding="utf-8")
    bootstrap = project.parent / "bootstrap"
    bootstrap.mkdir()
    (bootstrap / "pom.xml").write_text("""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>io.synexia.chromellm.tests</groupId>
  <artifactId>downloaded-runtime-bootstrap</artifactId><version>1</version><packaging>pom</packaging>
</project>
""", encoding="utf-8")
    # The standalone runtime has no library dependencies. Install a minimal GAV
    # descriptor, preserving the exact JAR bytes and avoiding its producer build.
    (bootstrap / "runtime-pom.xml").write_text("""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>io.synexia.chromellm</groupId>
  <artifactId>chromellm-standalone</artifactId><version>@@VERSION@@</version><packaging>jar</packaging>
</project>
""".replace("@@VERSION@@", version), encoding="utf-8")
    return repository / "io/synexia/chromellm/chromellm-standalone" / version / ("chromellm-standalone-" + version + ".jar")


def install_command(maven, project, repository, jar):
    bootstrap = project.parent / "bootstrap"
    return [maven, "-B", "-ntp", "-Dmaven.repo.local=" + str(repository), "-f", str(bootstrap / "pom.xml"),
            INSTALL_GOAL, "-Dfile=" + str(jar), "-DpomFile=" + str(bootstrap / "runtime-pom.xml"), "-DgeneratePom=false"]


def maven_command(maven, project, recipe, offline=False, repository=None):
    command = [maven, *(["-o"] if offline else []), "-B", "-ntp"]
    if repository is not None:
        command.append("-Dmaven.repo.local=" + str(repository))
    command.extend(["-f", str(project / "pom.xml")])
    if repository is None:
        command.append("-Psmart-build")
    command.extend(["-Dchromellm.smart.recipe=" + recipe.relative_to(project).as_posix(),
                    "verify" if repository is None else EXEC_GOAL])
    return command


def verify_class_origins(trace, jar, required, test_classes=None):
    """Check bounded HotSpot class-load records, including generated lambda hosts."""
    if trace.is_symlink() or not trace.is_file() or trace.stat().st_size > CLASS_TRACE_LIMIT or list(trace.parent.glob(trace.name + ".*")):
        raise AssertionError("Missing, oversized, or rotated class-load trace: " + str(trace))
    with trace.open("rb") as stream:
        contents = stream.read(CLASS_TRACE_LIMIT + 1)
    if len(contents) > CLASS_TRACE_LIMIT:
        raise AssertionError("Oversized class-load trace: " + str(trace))
    if not contents or not contents.endswith(b"\n"):
        raise AssertionError("Truncated or empty class-load trace: " + str(trace))
    origins, generated = {}, []
    for line in contents.decode("utf-8", errors="strict").splitlines():
        match = re.search(r"\[class,load\]\s+(\S+)\s+source:\s+(.+)$", line)
        if match is None or not match[1].startswith(JAVA_PACKAGE):
            continue
        name, origin = match[1], match[2]
        if "$$Lambda/" in name:
            generated.append((name, origin))
            continue
        parsed = urllib.parse.urlsplit(origin)
        if parsed.scheme != "file" or parsed.netloc not in ("", "localhost") or parsed.query or parsed.fragment:
            raise AssertionError("Non-file production class origin: " + name + " -> " + origin)
        actual = Path(urllib.request.url2pathname(parsed.path)).resolve(strict=True)
        expected = test_classes if test_classes is not None and (name == REAL_MODEL_TEST or name.startswith(REAL_MODEL_TEST + "$")) else jar
        if actual != expected.resolve(strict=True):
            raise AssertionError("Wrong class origin: " + name + " -> " + origin)
        if name in origins and origins[name] != origin:
            raise AssertionError("Conflicting class origins: " + name)
        origins[name] = origin
    for name, host in generated:
        declaring = name.split("$$Lambda/", 1)[0]
        # HotSpot may report an enclosing nest host for a nested class's lambda.
        # Both named owners must already have verified artifact origins; an
        # unrelated or merely name-shaped host is never enough.
        if declaring not in origins or host not in origins or not (host == declaring or declaring.startswith(host + "$")):
            raise AssertionError("Unverified generated class host: " + name)
    missing = set(required) - set(origins)
    if missing:
        raise AssertionError("Missing class-origin proof: " + ", ".join(sorted(missing)))
    return origins


def verify_process_class_origins(project, gate, jar, required, test_classes=None):
    """Validate each JVM independently; a launcher probe cannot supply missing runtime proof."""
    traces = []
    pattern = re.compile(re.escape(gate) + r"\.class-load-([1-9][0-9]*)\.log")
    for trace in project.glob(gate + ".class-load*"):
        if len(traces) == CLASS_TRACE_PROCESSES:
            raise AssertionError("Too many class-load processes for " + gate)
        if pattern.fullmatch(trace.name) is None:
            raise AssertionError("Unexpected or rotated process class-load trace: " + trace.name)
        traces.append(trace)
    if not traces:
        raise AssertionError("Missing process class-load traces for " + gate)
    records = []
    complete = []
    for trace in sorted(traces):
        # Version probes legitimately have no ChromeLLM classes. Any ChromeLLM
        # class they do load still has to satisfy the exact artifact-origin rule.
        origins = verify_class_origins(trace, jar, set(), test_classes)
        ready = set(required).issubset(origins)
        record = {"file": trace.name, "pid": int(pattern.fullmatch(trace.name)[1]),
                  "sha256": sha(trace), "bytes": trace.stat().st_size,
                  "classes": origins, "complete.runtime": ready}
        records.append(record)
        if ready:
            complete.append(record)
    if not complete:
        raise AssertionError("No single process contains the complete required runtime class-origin proof")
    # Keep the primary class map tied to one complete runtime, never a union of
    # fragments from different JVMs. Every other process is retained and checked.
    return {"runtime.trace": complete[0]["file"], "classes": complete[0]["classes"], "traces": records}


def run_consumer(command, project, environment, log, jar, metadata, expected_failure=None,
                 required=None, test_classes=None):
    verify_file(jar, metadata["java.sha256"], int(metadata["java.bytes"]), "Installed Java artifact")
    if (project / "src").exists() or (project / "target/classes").exists() or (project / "target/test-classes").exists():
        raise AssertionError("Consumer must have no project sources or compiled output")
    trace_name = log.stem + ".class-load-%p.log"
    if any(project.glob(log.stem + ".class-load*")):
        raise AssertionError("Class-load trace already exists")
    traced_environment = dict(environment)
    # Maven's POSIX launcher runs a Java version probe before the application.
    # %p gives each JVM its own file. A relative filename also avoids Windows
    # drive-letter colons being parsed as separators in an absolute -Xlog option.
    traced_environment["_JAVA_OPTIONS"] = (traced_environment.get("_JAVA_OPTIONS", "")
        + " -Xlog:class+load=info:file=" + trace_name + ":uptime,level,tags:filecount=1,filesize=8M")
    log.with_suffix(".command.json").write_text(json.dumps({"command": command, "cwd": str(project)}, indent=2) + "\n", encoding="utf-8")
    result = run(command, project, traced_environment, log, expected_failure)
    proof = verify_process_class_origins(project, log.stem, jar,
        required or {JAVA_PACKAGE + "SmartBuildMain"}, test_classes)
    verify_file(jar, metadata["java.sha256"], int(metadata["java.bytes"]), "Installed Java artifact")
    if (project / "src").exists() or (project / "target/classes").exists() or (project / "target/test-classes").exists():
        raise AssertionError("Consumer generated forbidden project sources or compiled output")
    log.with_suffix(".origin.json").write_text(json.dumps({
        "result": "PASS", "jar": str(jar), "java.sha256": metadata["java.sha256"],
        "java.bytes": int(metadata["java.bytes"]), **proof,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def compile_test_command(javac, source_project, jar, classes, empty_sourcepath):
    return [javac, "--release", "21", "-proc:none", "-Xlint:all", "-Werror", "-implicit:none",
            "-sourcepath", str(empty_sourcepath), "-cp", str(jar), "-d", str(classes),
            str(source_project / REAL_MODEL_SOURCE)]


def write_prompt(path):
    # Text-mode newline translation on Windows would change the request receipt.
    path.write_bytes(PROMPT.encode("utf-8"))


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
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--mode", choices=("producer", "artifact-consumer"), default="producer")
    parser.add_argument("--expected-commit", help="required ChromeLLM source commit for artifact-consumer mode")
    parser.add_argument("--expected-platform", choices=("linux-x86_64", "windows-x86_64", "macos-aarch64"))
    parser.add_argument("--expected-java-bytes", type=int,
                        help="explicit Java byte-length pin for a historical bundle without java.bytes")
    parser.add_argument("--expected-java-sha256",
                        help="explicit Java SHA-256 pin; also required for the historical byte-length override")
    parser.add_argument("--model-local", type=Path,
                        help="serve these exact released model bytes locally for a network-restricted qualification")
    args = parser.parse_args()
    consuming = args.mode == "artifact-consumer"
    if consuming and args.expected_commit is None:
        parser.error("artifact-consumer mode requires --expected-commit")
    if not consuming and any(value is not None for value in (args.expected_commit, args.expected_platform,
            args.expected_java_bytes, args.expected_java_sha256)):
        parser.error("artifact identity options require --mode artifact-consumer")
    resolved_maven = shutil.which(args.mvn)
    if resolved_maven is None:
        parser.error("Maven executable was not found: " + args.mvn)
    args.mvn = resolved_maven
    if consuming:
        for name in ("java", "javac", "keytool"):
            executable = shutil.which(getattr(args, name))
            if executable is None:
                parser.error("Consumer requires an available " + name + " executable")
            setattr(args, name, executable)
        for executable in ("git", "openssl"):
            if shutil.which(executable) is None:
                parser.error("Consumer requires an available " + executable + " executable")
    source_project = args.project.resolve(strict=True)
    project = source_project
    package = args.package.resolve(strict=True)
    metadata = (validate_consumer_package(project, package, args.expected_commit, args.expected_platform,
                args.expected_java_bytes, args.expected_java_sha256) if consuming
                else properties(package / "runtime.properties"))
    library = package / metadata["file"]
    native_sha, native_bytes = metadata["sha256"], int(metadata["bytes"])
    assert library.stat().st_size == native_bytes and sha(library) == native_sha, "package identity"
    assert metadata["compute"] == "cpu", "CPU artifact required"
    if args.model_local:
        args.model_local = args.model_local.resolve(strict=True)
        assert args.model_local.stat().st_size == MODEL_BYTES and sha(args.model_local) == MODEL_SHA

    (project / "target").mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="lazy-runtime-", dir=project / "target"))
    repository, installed_jar = None, None
    recipe_home = work
    if consuming:
        project = work / "consumer"
        repository = work / "maven-repository"
        installed_jar = prepare_consumer(project, repository, metadata["java.maven.version"])
        recipe_home = project / "fixture"
        recipe_home.mkdir()
        (work / "consumer-identity.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        command = install_command(args.mvn, project, repository, package / metadata["java.file"])
        (work / "install-downloaded-jar.command.json").write_text(json.dumps({
            "command": command, "cwd": str(project)}, indent=2) + "\n", encoding="utf-8")
        run(command, project, os.environ.copy(), work / "install-downloaded-jar.log")
        verify_file(installed_jar, metadata["java.sha256"], int(metadata["java.bytes"]), "Installed Java artifact")
    native_cache = work / "native-cache"
    model_cache = work / "model-cache"
    native_cache.mkdir()
    model_cache.mkdir()
    prompt = recipe_home / "prompt.txt"
    write_prompt(prompt)

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
        return maven_command(args.mvn, project, recipe, offline, repository)

    def execute(command, log, expected_failure=None, required=None, test_classes=None):
        if consuming:
            return run_consumer(command, project, environment, log, installed_jar, metadata,
                                expected_failure, required, test_classes)
        return run(command, project, environment, log, expected_failure)

    runtime_classes = {JAVA_PACKAGE + name for name in (
        "SmartBuildMain", "StandaloneMain", "ModelArtifactCache", "LazyModelBackend", "LlamaCppJniBackend")}
    cold = recipe_home / "cold.properties"
    write_properties(cold, values)
    try:
        execute(maven(cold), work / "cold.log", required=runtime_classes)
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
    warm = recipe_home / "warm.properties"
    write_properties(warm, values)
    execute(maven(warm, True), work / "warm-offline.log", required=runtime_classes)
    assert candidate(project, warm) == first, "fresh offline JVM must produce the same greedy CPU result"
    assert native.stat().st_mtime_ns == native_mtime and model.stat().st_mtime_ns == model_mtime, "warm cache was rewritten"
    assert sha(native) == native_sha and sha(model) == MODEL_SHA, "warm cache bytes changed"

    # Refuse a valid model with an absent or corrupted native artifact before loading it.
    before = outputs(project)
    empty = work / "empty-native-cache"
    empty.mkdir()
    missing = recipe_home / "missing.properties"
    write_properties(missing, dict(values, jniCacheDir=empty))
    execute(maven(missing, True), work / "missing-offline.log", "offline")
    assert outputs(project) == before, "offline native miss published output"
    with native.open("r+b") as stream:
        stream.seek(-1, os.SEEK_END)
        original = stream.read(1)
        stream.seek(-1, os.SEEK_END)
        stream.write(bytes([original[0] ^ 1]))
    try:
        execute(maven(warm, True), work / "corrupt-native.log", "SHA-256")
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
        execute(maven(warm, True), work / "corrupt-model.log", "SHA-256")
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
    test_classes = None
    if consuming:
        test_classes, empty_sourcepath = work / "contract-classes", work / "empty-sourcepath"
        test_classes.mkdir()
        empty_sourcepath.mkdir()
        verify_checkout(source_project, args.expected_commit)
        command = compile_test_command(args.javac, source_project, installed_jar, test_classes, empty_sourcepath)
        (work / "compile-real-model-contract.command.json").write_text(json.dumps({
            "command": command, "cwd": str(project)}, indent=2) + "\n", encoding="utf-8")
        run(command, project, environment, work / "compile-real-model-contract.log")
        compiled = {path.relative_to(test_classes).as_posix(): sha(path) for path in test_classes.rglob("*") if path.is_file()}
        expected_class = REAL_MODEL_TEST.replace(".", "/") + ".class"
        if set(compiled) != {expected_class}:
            raise AssertionError("Only the unchanged real-model test closure may be compiled")
        (work / "compiled-test-closure.json").write_text(json.dumps({
            "source": REAL_MODEL_SOURCE, "source.sha256": metadata["real.model.test.sha256"],
            "java.sha256": metadata["java.sha256"], "classes": compiled,
        }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        classpath = os.pathsep.join((str(installed_jar), str(test_classes)))
    execute([args.java, "-cp", classpath, REAL_MODEL_TEST,
         str(native), origin + "/offline-model.gguf", MODEL_SHA, str(model_cache),
         str(MODEL_BYTES), "--offline=true"],
        work / "real-model-contract.log", required={REAL_MODEL_TEST, JAVA_PACKAGE + "ModelArtifactCache",
            JAVA_PACKAGE + "LlamaCppJniBackend"}, test_classes=test_classes)

    if consuming:
        verify_file(package / metadata["java.file"], metadata["java.sha256"], int(metadata["java.bytes"]), "Downloaded Java artifact")
        verify_file(installed_jar, metadata["java.sha256"], int(metadata["java.bytes"]), "Installed Java artifact")
        verify_checkout(source_project, args.expected_commit)

    write_properties(work / "result.properties", {
        "result": "PASS", "native.sha256": native_sha, "native.bytes": native_bytes,
        "model.sha256": MODEL_SHA, "model.bytes": MODEL_BYTES,
        "native.downloads": transferred["native"], "native.redirect": "https-relative",
        "model.source": "verified-local-release-bytes" if args.model_local else MODEL_URL,
        "cold.inference": "pass", "warm.offline.inference": "pass", "native.corruption": "rejected",
        "offline.native.miss": "rejected", "model.corruption": "rejected", "real.model.lifecycle": "pass",
        **({"mode": args.mode, "java.sha256": metadata["java.sha256"], "java.bytes": metadata["java.bytes"],
            "java.class.origins": "verified-installed-jar", "source.commit": args.expected_commit,
            "platform": metadata["platform"]} if consuming else {}),
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
