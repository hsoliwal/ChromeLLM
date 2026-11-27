#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Deterministic artifact-consumer gates; no model, native code, Maven, or JVM runs."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from unittest.mock import patch
import warnings
import xml.etree.ElementTree as ET
import zipfile


SOURCE = Path(os.environ.get("CHROMELLM_LAZY_SMOKE_SOURCE", Path(__file__).with_name("lazy_runtime_smoke.py")))
SPEC = importlib.util.spec_from_file_location("lazy_runtime_smoke", SOURCE)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
COMMIT = "26ca6243c1cd11bb1ef1e7852e3aee60609fdeaf"
NATIVE_COMMIT = "889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b"
COORDINATES = "META-INF/maven/io.synexia.chromellm/chromellm-standalone/pom.properties"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project, self.package = self.root / "source", self.root / "package"
        self.project.mkdir()
        self.package.mkdir()
        for relative in [*smoke.NATIVE_SOURCES.values(), smoke.REAL_MODEL_SOURCE]:
            path = self.project / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("Unexecuted source fixture: " + relative + "\n").encode())
        (self.package / "fixture.so").write_bytes(b"Unexecuted deterministic native fixture\x00")
        self.jar = self.package / "runtime.jar"
        self.write_jar()
        self.metadata = {
            "schema": "1", "file": "fixture.so", "compute": "cpu",
            "sha256": smoke.sha(self.package / "fixture.so"), "bytes": str((self.package / "fixture.so").stat().st_size),
            "os": "Linux", "arch": "x86_64", "platform": "linux-x86_64",
            "llama.commit": NATIVE_COMMIT, "chromellm.commit": COMMIT,
            "java.file": "runtime.jar", "java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size),
            **{key: smoke.sha(self.project / relative) for key, relative in smoke.NATIVE_SOURCES.items()},
        }
        self.persist()
        self.host = mock.patch.object(smoke.platform, "system", return_value="Linux")
        self.machine = mock.patch.object(smoke.platform, "machine", return_value="x86_64")
        self.checkout = mock.patch.object(smoke, "verify_checkout")
        self.host.start()
        self.machine.start()
        self.checkout_check = self.checkout.start()
        self.addCleanup(self.host.stop)
        self.addCleanup(self.machine.stop)
        self.addCleanup(self.checkout.stop)

    def write_jar(self, group="io.synexia.chromellm", artifact="chromellm-standalone", version="0.1.0-SNAPSHOT", duplicate=False):
        contents = "groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr(zipfile.ZipInfo(COORDINATES), contents)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(zipfile.ZipInfo(COORDINATES), contents)

    def persist(self):
        smoke.write_properties(self.package / "runtime.properties", self.metadata)

    def validate(self, **kwargs):
        kwargs.setdefault("environment", {})
        return smoke.validate_consumer_package(self.project, self.package, COMMIT, **kwargs)

    def test_complete_manifest_and_exact_checkout_are_required(self):
        result = self.validate(expected_platform="linux-x86_64")
        self.assertEqual("runtime.properties", result["java.bytes.authority"])
        self.assertEqual("0.1.0-SNAPSHOT", result["java.maven.version"])
        self.assertEqual(smoke.sha(self.project / smoke.REAL_MODEL_SOURCE), result["real.model.test.sha256"])
        self.checkout_check.assert_called_once_with(self.project, COMMIT)

    def test_historical_manifest_requires_both_caller_pins(self):
        size, digest = self.metadata.pop("java.bytes"), self.metadata["java.sha256"]
        self.persist()
        for kwargs in ({}, {"expected_java_bytes": int(size)}, {"expected_java_sha256": digest}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(AssertionError, "Missing java.bytes"):
                self.validate(**kwargs)
        result = self.validate(expected_java_bytes=int(size), expected_java_sha256=digest)
        self.assertEqual(size, result["java.bytes"])
        self.assertEqual("explicit-historical-caller-pin", result["java.bytes.authority"])

    def test_caller_pins_must_agree_even_when_manifest_has_size(self):
        size, digest = int(self.metadata["java.bytes"]), self.metadata["java.sha256"]
        self.validate(expected_java_bytes=size, expected_java_sha256=digest)
        with self.assertRaisesRegex(AssertionError, "byte length disagrees"):
            self.validate(expected_java_bytes=size + 1)
        with self.assertRaisesRegex(AssertionError, "SHA-256 disagrees"):
            self.validate(expected_java_sha256="a" * 64)

    def test_historical_override_cannot_replace_manifest_digest(self):
        self.metadata.pop("java.bytes")
        self.persist()
        with self.assertRaisesRegex(AssertionError, "SHA-256 disagrees"):
            self.validate(expected_java_bytes=self.jar.stat().st_size, expected_java_sha256="a" * 64)

    def test_source_and_workflow_commits_must_agree(self):
        with self.assertRaisesRegex(AssertionError, "Workflow source commit"):
            self.validate(environment={"GITHUB_SHA": "b" * 40})
        self.metadata["chromellm.commit"] = "b" * 40
        self.persist()
        with self.assertRaisesRegex(AssertionError, "Package source commit"):
            self.validate()
        self.checkout_check.assert_not_called()

    def test_commit_is_required_and_full_length(self):
        for commit in (None, "", COMMIT[:7], "G" * 40):
            with self.subTest(commit=commit), self.assertRaisesRegex(AssertionError, "expected ChromeLLM commit"):
                smoke.validate_consumer_package(self.project, self.package, commit)

    def test_native_source_environment_is_bound(self):
        for name in ("NATIVE_SOURCE_COMMIT", "LLAMA_COMMIT"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "native source commit"):
                self.validate(environment={name: "b" * 40})
        self.validate(environment={"GITHUB_SHA": COMMIT, "NATIVE_SOURCE_COMMIT": NATIVE_COMMIT})

    def test_all_three_native_source_pins_are_checked(self):
        for key in smoke.NATIVE_SOURCES:
            saved = self.metadata[key]
            self.metadata[key] = "a" * 64
            self.persist()
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, "Native source identity"):
                self.validate()
            self.metadata[key] = saved
        self.checkout_check.assert_not_called()

    def test_native_corruption_and_size_mismatch_are_rejected(self):
        library = self.package / "fixture.so"
        contents = library.read_bytes()
        library.write_bytes(bytes([contents[0] ^ 1]) + contents[1:])
        with self.assertRaisesRegex(AssertionError, "Native artifact identity"):
            self.validate()
        library.write_bytes(contents + b"x")
        with self.assertRaisesRegex(AssertionError, "Native artifact identity"):
            self.validate()

    def test_java_corruption_and_size_mismatch_are_rejected(self):
        contents = self.jar.read_bytes()
        self.jar.write_bytes(bytes([contents[0] ^ 1]) + contents[1:])
        with self.assertRaisesRegex(AssertionError, "Java artifact identity"):
            self.validate()
        self.jar.write_bytes(contents + b"x")
        with self.assertRaisesRegex(AssertionError, "Java artifact identity"):
            self.validate()

    def test_native_lengths_are_positive_exact_decimal(self):
        for value in ("0", "-1", "1.0", "+4", "01", "NaN"):
            self.metadata["bytes"] = value
            self.persist()
            with self.subTest(value=value), self.assertRaisesRegex(AssertionError, "native byte length"):
                self.validate()

    def test_native_digest_format_is_strict(self):
        for value in ("A" * 64, "0" * 63, "z" * 64):
            self.metadata["sha256"] = value
            self.persist()
            with self.subTest(value=value), self.assertRaisesRegex(AssertionError, "native SHA-256"):
                self.validate()

    def test_package_platform_and_expected_host_are_checked(self):
        with self.assertRaisesRegex(AssertionError, "Expected platform"):
            self.validate(expected_platform="windows-x86_64")
        self.metadata["platform"] = "windows-x86_64"
        self.persist()
        with self.assertRaisesRegex(AssertionError, "Package platform"):
            self.validate()
        self.metadata["platform"] = "linux-x86_64"
        self.metadata["os"] = "Windows"
        self.persist()
        with self.assertRaisesRegex(AssertionError, "Package platform"):
            self.validate()

    def test_arch_aliases_and_unsupported_hosts(self):
        self.assertEqual("linux-x86_64", smoke.platform_name("Linux", "amd64"))
        self.assertEqual("windows-x86_64", smoke.platform_name("Windows", "AMD64"))
        self.assertEqual("macos-aarch64", smoke.platform_name("Darwin", "arm64"))
        with self.assertRaisesRegex(AssertionError, "Unsupported runtime platform"):
            smoke.platform_name("Linux", "riscv64")

    def test_duplicate_metadata_is_rejected_before_processes(self):
        with (self.package / "runtime.properties").open("a", encoding="utf-8") as stream:
            stream.write("java.bytes=1\n")
        with self.assertRaisesRegex(AssertionError, "Ambiguous"):
            self.validate()
        self.checkout_check.assert_not_called()

    def test_malformed_or_oversized_metadata_is_rejected(self):
        for contents in (b"not a property\n", b"key=value\nkey=other\n", b" key=value\n", b"a" * (64 * 1024 + 1)):
            with self.subTest(contents=contents[:30]), self.assertRaises(AssertionError):
                smoke.strict_properties(contents, "fixture")

    def test_package_paths_cannot_escape(self):
        for name in (None, "..", "../runtime.jar", "a/runtime.jar", "a\\runtime.jar", "C:runtime.jar"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "flat filename"):
                smoke.package_file(self.package, name)

    def test_symbolic_package_entries_are_rejected(self):
        with mock.patch.object(Path, "is_symlink", return_value=True):
            with self.assertRaisesRegex(AssertionError, "regular file"):
                smoke.package_file(self.package, "runtime.jar")

    def test_cpu_schema_is_explicit(self):
        for key, value in (("schema", "2"), ("compute", "webgpu")):
            old = self.metadata[key]
            self.metadata[key] = value
            self.persist()
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, "Schema 1 CPU"):
                self.validate()
            self.metadata[key] = old

    def test_embedded_coordinates_cannot_substitute_another_artifact(self):
        for group, artifact, version in (("other", "chromellm-standalone", "1"),
                ("io.synexia.chromellm", "other", "1"), ("io.synexia.chromellm", "chromellm-standalone", "../x")):
            self.write_jar(group, artifact, version)
            self.metadata.update({"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)})
            self.persist()
            with self.subTest(group=group, artifact=artifact, version=version), self.assertRaises(AssertionError):
                self.validate()

    def test_duplicate_embedded_coordinates_are_rejected(self):
        self.write_jar(duplicate=True)
        self.metadata.update({"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)})
        self.persist()
        with self.assertRaisesRegex(AssertionError, "ambiguous embedded"):
            self.validate()


class CheckoutTests(unittest.TestCase):
    def test_git_commit_and_all_source_bytes_are_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "standalone"
            paths = [*smoke.NATIVE_SOURCES.values(), smoke.REAL_MODEL_SOURCE]
            for relative in paths:
                path = project / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((relative + "\n").encode())
            def git(*args):
                return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True, timeout=30).stdout.strip()
            git("init", "-q")
            git("add", "standalone")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "-m", "Unexecuted source identity fixture")
            commit = git("rev-parse", "HEAD")
            smoke.verify_checkout(project, commit)
            with self.assertRaisesRegex(AssertionError, "source commit"):
                smoke.verify_checkout(project, "b" * 40)
            for relative in paths:
                path = project / relative
                contents = path.read_bytes()
                path.write_bytes(contents + b"changed\n")
                with self.subTest(relative=relative), self.assertRaisesRegex(AssertionError, "source has changed"):
                    smoke.verify_checkout(project, commit)
                path.write_bytes(contents)


class CommandTests(unittest.TestCase):
    def test_producer_command_preserves_verify_and_offline(self):
        project = Path("fixture")
        recipe = project / "target" / "cold.properties"
        self.assertEqual(["mvn", "-o", "-B", "-ntp", "-f", str(project / "pom.xml"), "-Psmart-build",
            "-Dchromellm.smart.recipe=target/cold.properties", "verify"],
            smoke.maven_command("mvn", project, recipe, True))

    def test_consumer_uses_only_explicit_goal_and_isolated_repository(self):
        project, repository = Path("consumer"), Path("isolated-repository")
        recipe = project / "fixture" / "warm.properties"
        command = smoke.maven_command("mvn", project, recipe, True, repository)
        self.assertEqual(smoke.EXEC_GOAL, command[-1])
        self.assertIn("-o", command)
        self.assertIn("-Dmaven.repo.local=" + str(repository), command)
        self.assertNotIn("verify", command)
        self.assertNotIn("compile", command)
        self.assertNotIn("-Psmart-build", command)

    def test_source_free_pom_has_exact_dependency_and_maven_offline_argument(self):
        root = ET.fromstring(smoke.consumer_pom("0.1.0-SNAPSHOT"))
        dependencies = root.findall("m:dependencies/m:dependency", NAMESPACE)
        self.assertEqual(1, len(dependencies))
        self.assertEqual("chromellm-standalone", dependencies[0].findtext("m:artifactId", namespaces=NAMESPACE))
        self.assertEqual("0.1.0-SNAPSHOT", dependencies[0].findtext("m:version", namespaces=NAMESPACE))
        self.assertEqual("pom", root.findtext("m:packaging", namespaces=NAMESPACE))
        self.assertIsNone(root.find("m:parent", NAMESPACE))
        plugins = root.findall("m:build/m:plugins/m:plugin", NAMESPACE)
        self.assertEqual(1, len(plugins))
        self.assertEqual("exec-maven-plugin", plugins[0].findtext("m:artifactId", namespaces=NAMESPACE))
        configuration = plugins[0].find("m:executions/m:execution/m:configuration", NAMESPACE)
        self.assertEqual("false", configuration.findtext("m:addOutputToClasspath", namespaces=NAMESPACE))
        self.assertEqual("false", configuration.findtext("m:includePluginDependencies", namespaces=NAMESPACE))
        self.assertIsNone(configuration.find("m:additionalClasspathElements", NAMESPACE))
        arguments = [element.text for element in configuration.findall("m:arguments/m:argument", NAMESPACE)]
        self.assertEqual(["${chromellm.smart.recipe}", "${project.basedir}", "${project.build.directory}",
                          "--offline=${settings.offline}"], arguments)

    def test_bootstrap_keeps_the_exact_jar_and_minimal_descriptor(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project, repository, jar = root / "consumer", root / "repository", root / "downloaded.jar"
            installed = smoke.prepare_consumer(project, repository, "0.1.0-SNAPSHOT")
            expected = repository / "io/synexia/chromellm/chromellm-standalone/0.1.0-SNAPSHOT/chromellm-standalone-0.1.0-SNAPSHOT.jar"
            self.assertEqual(expected, installed)
            command = smoke.install_command("mvn", project, repository, jar)
            self.assertIn(smoke.INSTALL_GOAL, command)
            self.assertIn("-Dfile=" + str(jar), command)
            self.assertIn("-DgeneratePom=false", command)
            self.assertIn("-Dmaven.repo.local=" + str(repository), command)
            self.assertNotIn(str(project / "pom.xml"), command)
            runtime_pom = ET.parse(root / "bootstrap/runtime-pom.xml").getroot()
            self.assertIsNone(runtime_pom.find("m:build", NAMESPACE))
            self.assertIsNone(runtime_pom.find("m:dependencies", NAMESPACE))
            self.assertFalse((project / "src").exists())
            self.assertTrue((project / "target").is_dir())
            self.assertFalse((project / "target/classes").exists())

    def test_test_compiler_has_empty_sourcepath_and_only_unchanged_test_source(self):
        project, jar, classes, empty = Path("source"), Path("verified.jar"), Path("contract-classes"), Path("empty")
        command = smoke.compile_test_command("javac", project, jar, classes, empty)
        self.assertEqual([str(project / smoke.REAL_MODEL_SOURCE)], [arg for arg in command if arg.endswith(".java")])
        self.assertEqual(str(jar), command[command.index("-cp") + 1])
        self.assertEqual(str(empty), command[command.index("-sourcepath") + 1])
        self.assertIn("-implicit:none", command)
        self.assertIn("-proc:none", command)
        self.assertIn("-Werror", command)

    def test_prompt_bytes_do_not_depend_on_windows_newline_translation(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "prompt.txt"
            with mock.patch.object(Path, "write_text", side_effect=AssertionError("text-mode write is forbidden")):
                smoke.write_prompt(path)
            self.assertEqual(smoke.PROMPT.encode("utf-8"), path.read_bytes())
            self.assertNotIn(b"\r", path.read_bytes())

    def test_consumer_commit_is_mandatory_before_processes(self):
        arguments = ["smoke", "--project", ".", "--package", ".", "--mode", "artifact-consumer"]
        with mock.patch("sys.argv", arguments), mock.patch("sys.stderr", new_callable=io.StringIO), \
                mock.patch.object(smoke.subprocess, "run") as process, self.assertRaises(SystemExit) as refusal:
            smoke.main()
        self.assertEqual(2, refusal.exception.code)
        process.assert_not_called()


class OriginTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "consumer"
        self.project.mkdir()
        self.jar = self.root / "installed jar.jar"
        self.jar.write_bytes(b"Unexecuted installed JAR identity fixture")
        self.trace = self.project / "cold.class-load-1234.log"
        self.main = smoke.JAVA_PACKAGE + "SmartBuildMain"

    def trace_line(self, name, origin=None):
        return "[0.500s][info][class,load] " + name + " source: " + (origin or self.jar.as_uri()) + "\n"

    def test_exact_jar_origin_and_bound_lambda_host_pass(self):
        self.trace.write_text(self.trace_line(self.main) + self.trace_line(self.main + "$$Lambda/0x123", self.main), encoding="utf-8")
        self.assertEqual({self.main: self.jar.as_uri()}, smoke.verify_class_origins(self.trace, self.jar, {self.main}))

    def test_test_closure_is_the_only_allowed_directory_origin(self):
        classes = self.root / "contract-classes"
        classes.mkdir()
        self.trace.write_text(self.trace_line(self.main) + self.trace_line(smoke.REAL_MODEL_TEST, classes.as_uri() + "/"), encoding="utf-8")
        origins = smoke.verify_class_origins(self.trace, self.jar, {self.main, smoke.REAL_MODEL_TEST}, classes)
        self.assertEqual(2, len(origins))
        with self.assertRaisesRegex(AssertionError, "Wrong class origin"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})

    def test_other_jar_and_compiled_production_directory_are_rejected(self):
        other = self.root / "other.jar"
        other.write_bytes(self.jar.read_bytes())
        classes = self.project / "target/classes"
        classes.mkdir(parents=True)
        for origin in (other.as_uri(), classes.as_uri() + "/"):
            self.trace.write_text(self.trace_line(self.main, origin), encoding="utf-8")
            with self.subTest(origin=origin), self.assertRaisesRegex(AssertionError, "Wrong class origin"):
                smoke.verify_class_origins(self.trace, self.jar, {self.main})

    def test_missing_required_classes_and_unknown_lambda_hosts_are_rejected(self):
        self.trace.write_text(self.trace_line(smoke.JAVA_PACKAGE + "ModelRequest"), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Missing class-origin proof"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})
        self.trace.write_text(self.trace_line(self.main + "$$Lambda/0x123", self.main), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Unverified generated class host"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})

    def test_non_file_origin_is_rejected(self):
        self.trace.write_text(self.trace_line(self.main, "__JVM_DefineClass__"), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Non-file production class origin"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})

    def test_bounded_complete_trace_is_required(self):
        with self.assertRaisesRegex(AssertionError, "Missing, oversized, or rotated"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})
        self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
        rotated = self.trace.with_name(self.trace.name + ".0")
        rotated.write_text("earlier data", encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "rotated"):
            smoke.verify_class_origins(self.trace, self.jar, {self.main})
        rotated.unlink()
        with mock.patch.object(smoke, "CLASS_TRACE_LIMIT", 4):
            with self.assertRaisesRegex(AssertionError, "oversized"):
                smoke.verify_class_origins(self.trace, self.jar, {self.main})

    def test_command_runner_checks_origin_and_installed_bytes_after_execution(self):
        metadata = {"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)}
        log = self.root / "cold.log"
        original_environment = {"_JAVA_OPTIONS": "-Xcheck:jni"}
        def process(command, **kwargs):
            self.assertEqual(self.project, kwargs["cwd"])
            self.assertIn("file=cold.class-load-%p.log:", kwargs["env"]["_JAVA_OPTIONS"])
            self.assertIn("filesize=8M", kwargs["env"]["_JAVA_OPTIONS"])
            self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
            kwargs["stdout"].write("Unexecuted process fixture\n")
            return subprocess.CompletedProcess(command, 0)
        with mock.patch.object(smoke.subprocess, "run", side_effect=process):
            smoke.run_consumer(["unexecuted-maven"], self.project, original_environment, log, self.jar, metadata)
        self.assertEqual({"_JAVA_OPTIONS": "-Xcheck:jni"}, original_environment)
        receipt = json.loads(log.with_suffix(".origin.json").read_text(encoding="utf-8"))
        self.assertEqual("PASS", receipt["result"])
        self.assertEqual(metadata["java.sha256"], receipt["java.sha256"])
        self.assertEqual({self.main: self.jar.as_uri()}, receipt["classes"])

    def test_compiled_source_fallback_is_refused_before_execution(self):
        metadata = {"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)}
        (self.project / "target/classes").mkdir(parents=True)
        with mock.patch.object(smoke.subprocess, "run") as process, self.assertRaisesRegex(AssertionError, "no project sources"):
            smoke.run_consumer(["unexecuted-maven"], self.project, {}, self.root / "cold.log", self.jar, metadata)
        process.assert_not_called()

    def test_installed_jar_mutation_during_execution_is_rejected(self):
        metadata = {"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)}
        def process(command, **kwargs):
            self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
            contents = self.jar.read_bytes()
            self.jar.write_bytes(bytes([contents[0] ^ 1]) + contents[1:])
            return subprocess.CompletedProcess(command, 0)
        with mock.patch.object(smoke.subprocess, "run", side_effect=process), self.assertRaisesRegex(AssertionError, "Installed Java artifact identity"):
            smoke.run_consumer(["unexecuted-maven"], self.project, {}, self.root / "cold.log", self.jar, metadata)

    def test_process_cannot_create_a_compiled_output_fallback(self):
        metadata = {"java.sha256": smoke.sha(self.jar), "java.bytes": str(self.jar.stat().st_size)}
        def process(command, **kwargs):
            self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
            (self.project / "target/classes").mkdir(parents=True)
            return subprocess.CompletedProcess(command, 0)
        with mock.patch.object(smoke.subprocess, "run", side_effect=process), self.assertRaisesRegex(AssertionError, "forbidden project sources"):
            smoke.run_consumer(["unexecuted-maven"], self.project, {}, self.root / "cold.log", self.jar, metadata)

    def test_launcher_probe_and_one_complete_runtime_have_separate_proofs(self):
        probe = self.project / "cold.class-load-1233.log"
        probe.write_text("[0.020s][info][class,load] java.lang.Object source: shared objects file\n", encoding="utf-8")
        other = smoke.JAVA_PACKAGE + "StandaloneMain"
        self.trace.write_text(self.trace_line(self.main) + self.trace_line(other), encoding="utf-8")
        proof = smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main, other})
        self.assertEqual(self.trace.name, proof["runtime.trace"])
        self.assertEqual({self.main: self.jar.as_uri(), other: self.jar.as_uri()}, proof["classes"])
        self.assertEqual(2, len(proof["traces"]))
        self.assertEqual({}, proof["traces"][0]["classes"])
        self.assertFalse(proof["traces"][0]["complete.runtime"])
        self.assertTrue(proof["traces"][1]["complete.runtime"])

    def test_required_runtime_classes_cannot_be_assembled_across_processes(self):
        other = smoke.JAVA_PACKAGE + "StandaloneMain"
        self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
        (self.project / "cold.class-load-1235.log").write_text(self.trace_line(other), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "No single process"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main, other})

    def test_every_probe_trace_is_checked_for_non_file_chromellm_origins(self):
        self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
        other = smoke.JAVA_PACKAGE + "ModelArtifactCache"
        (self.project / "cold.class-load-1233.log").write_text(self.trace_line(other, "__JVM_DefineClass__"), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Non-file production class origin"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_rotated_process_trace_is_rejected_even_with_a_complete_runtime(self):
        self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
        (self.project / "cold.class-load-1233.log.0").write_text("probe\n", encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Unexpected or rotated process"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_truncated_process_trace_is_rejected(self):
        self.trace.write_text(self.trace_line(self.main).rstrip("\n"), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Truncated or empty"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_process_trace_count_is_bounded(self):
        for pid in range(1, smoke.CLASS_TRACE_PROCESSES + 2):
            (self.project / ("cold.class-load-" + str(pid) + ".log")).write_text(self.trace_line(self.main), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Too many class-load processes"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_process_trace_must_be_a_regular_file(self):
        self.trace.mkdir()
        with self.assertRaisesRegex(AssertionError, "Missing, oversized, or rotated"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_oversized_probe_is_rejected_even_with_a_complete_runtime(self):
        self.trace.write_text(self.trace_line(self.main), encoding="utf-8")
        (self.project / "cold.class-load-1233.log").write_bytes(b"x" * 1024 + b"\n")
        with mock.patch.object(smoke, "CLASS_TRACE_LIMIT", 1024), self.assertRaisesRegex(AssertionError, "oversized"):
            smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})

    def test_nested_lambda_may_report_its_verified_enclosing_host(self):
        host = smoke.JAVA_PACKAGE + "ModelArtifactCache"
        declaring = host + "$DownloadLifetime"
        self.trace.write_text(self.trace_line(self.main) + self.trace_line(host) + self.trace_line(declaring)
            + self.trace_line(declaring + "$$Lambda/0x123", host), encoding="utf-8")
        proof = smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main, host, declaring})
        self.assertEqual(3, len(proof["classes"]))
        self.assertTrue(proof["traces"][0]["complete.runtime"])

    def test_nested_lambda_requires_the_declaring_class_and_actual_host(self):
        host = smoke.JAVA_PACKAGE + "ModelArtifactCache"
        declaring = host + "$DownloadLifetime"
        unrelated = smoke.JAVA_PACKAGE + "StandaloneMain"
        cases = (
            self.trace_line(host) + self.trace_line(declaring + "$$Lambda/0x123", host),
            self.trace_line(declaring) + self.trace_line(declaring + "$$Lambda/0x123", host),
            self.trace_line(declaring) + self.trace_line(unrelated) + self.trace_line(declaring + "$$Lambda/0x123", unrelated),
        )
        for contents in cases:
            self.trace.write_text(self.trace_line(self.main) + contents, encoding="utf-8")
            with self.subTest(contents=contents), self.assertRaisesRegex(AssertionError, "Unverified generated class host"):
                smoke.verify_process_class_origins(self.project, "cold", self.jar, {self.main})


# Preserve the four offline prompt regressions from ChromeLLM commit
# 13dd3a197d08df3e41ed3e82bd20b07a43489b62 (Apache-2.0, same copyright).
class StopBeforeAcquisition(Exception):
    """The staged prompt is observable before TLS, Maven, or inference starts."""


class LazyRuntimePromptTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="chromellm-prompt-")
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name) / "project"
        (self.project / "target").mkdir(parents=True)
        self.package = Path(temporary.name) / "package"
        self.package.mkdir()
        native = self.package / "fixture.bin"
        native.write_bytes(b"not-a-native-library")
        smoke.write_properties(self.package / "runtime.properties", {
            "file": native.name, "sha256": smoke.sha(native),
            "bytes": native.stat().st_size, "compute": "cpu",
        })
        self.recipe = self.project / "recipe.properties"
        self.recipe.write_bytes(b"backend=jni\n")

    def stage_prompt(self, text_newline):
        original_write_text = Path.write_text

        def platform_write_text(path, data, encoding=None, errors=None, newline=None):
            # TextIOWrapper's Windows default can be reproduced on every CI host.
            return original_write_text(path, data, encoding=encoding, errors=errors,
                                       newline=text_newline if newline is None else newline)

        def stop(command, **_):
            self.assertEqual("openssl", command[0])
            raise StopBeforeAcquisition()

        arguments = [str(SOURCE), "--project", str(self.project),
                     "--package", str(self.package), "--mvn", sys.executable]
        with patch.object(sys, "argv", arguments), \
                patch.object(Path, "write_text", platform_write_text), \
                patch.object(smoke.subprocess, "run", side_effect=stop), \
                self.assertRaises(StopBeforeAcquisition):
            smoke.main()
        staged = list((self.project / "target").glob("lazy-runtime-*/prompt.txt"))
        self.assertEqual(1, len(staged))
        return staged[0]

    def receipt(self, prompt_hash):
        output_directory = self.project / "target/smart-build"
        output_directory.mkdir(exist_ok=True)
        candidate = output_directory / "candidate-fixture.txt"
        candidate.write_bytes(b"A fixture reply.\n")
        smoke.write_properties(output_directory / "receipt-fixture.properties", {
            "recipe.sha256": smoke.sha(self.recipe), "backend": "llama.cpp-jni",
            "prompt.sha256": prompt_hash, "candidate.sha256": smoke.sha(candidate),
        })

    def assert_prompt_receipt(self, text_newline):
        prompt = self.stage_prompt(text_newline)
        # Production hashes the bytes actually read; the fixture must agree.
        self.receipt(smoke.sha(prompt))
        self.assertEqual("A fixture reply.\n", smoke.candidate(self.project, self.recipe))
        self.assertEqual(smoke.PROMPT.encode("utf-8"), prompt.read_bytes())

    def test_lf_prompt_receipt(self):
        self.assert_prompt_receipt("\n")

    def test_windows_text_translation_preserves_prompt_receipt(self):
        self.assert_prompt_receipt("\r\n")

    def test_unicode_and_existing_crlf_are_preserved_on_windows(self):
        with patch.object(smoke, "PROMPT", "  नमस्ते é 🦙\r\nsecond line\n\t"):
            self.assert_prompt_receipt("\r\n")

    def test_changed_newlines_are_still_rejected(self):
        changed = smoke.PROMPT.replace("\n", "\r\n").encode("utf-8")
        self.receipt(hashlib.sha256(changed).hexdigest())
        with self.assertRaisesRegex(AssertionError, "prompt receipt"):
            smoke.candidate(self.project, self.recipe)


if __name__ == "__main__":
    unittest.main()
