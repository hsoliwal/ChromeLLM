#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Executable action-boundary contracts. The Java process is a named test double."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[4]
ACTION = ROOT / ".github/actions/build-ai/run.sh"
FAKE_JAVA = r'''
import json, os, signal, sys, time
from pathlib import Path
args = sys.argv[1:]
prompt = Path(args[args.index("--prompt-file") + 1]).read_text(encoding="utf-8", errors="strict")
Path(os.environ["FAKE_CAPTURE"]).write_text(json.dumps({"argv": args, "prompt": prompt, "pid": os.getpid()}))
if os.environ.get("FAKE_WAIT") == "true":
    def stop(signum, frame):
        Path(os.environ["FAKE_STOPPED"]).write_text(str(signum))
        sys.exit(128 + signum)
    signal.signal(signal.SIGTERM, stop)
    while True:
        time.sleep(0.05)
print(os.environ.get("FAKE_ADVICE", "Review the reported failure and add a regression test."))
print("fixture diagnostic", file=sys.stderr)
sys.exit(int(os.environ.get("FAKE_EXIT", "0")))
'''


class BuildAiActionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="chromellm-action-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        java = self.bin / "java"
        java.write_text("#!" + sys.executable + "\n" + FAKE_JAVA)
        java.chmod(0o700)
        self.jar = self.root / "engine with spaces.jar"
        self.jar.write_bytes(b"explicitly fake Java artifact for action boundary contracts")
        self.packet = self.root / "review packet.txt"
        self.packet.write_text("Check the changed cache ownership contract.\n", encoding="utf-8")
        self.log = self.root / "build.log"
        self.log.write_text("[ERROR] ExampleTest failed: expected 2, actual 3\n", encoding="utf-8")
        self.capture = self.root / "captured.json"
        self.output = self.root / "outputs"
        self.summary = self.root / "summary"
        self.env = dict(os.environ)
        for key in tuple(self.env):
            if key.startswith("BUILD_AI_") or key.startswith("FAKE_"):
                self.env.pop(key)
        self.env.update({
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "RUNNER_TEMP": str(self.root),
            "GITHUB_OUTPUT": str(self.output),
            "GITHUB_STEP_SUMMARY": str(self.summary),
            "FAKE_CAPTURE": str(self.capture),
            "FAKE_STOPPED": str(self.root / "stopped"),
            "BUILD_AI_PHASE": "pre-build",
            "BUILD_AI_JAR": str(self.jar),
            "BUILD_AI_JAR_SHA256": hashlib.sha256(self.jar.read_bytes()).hexdigest(),
            "BUILD_AI_PROVIDER": "chrome",
            "BUILD_AI_CHROME_PROFILE": str(self.root / "dedicated profile"),
            "BUILD_AI_PROMPT_FILE": str(self.packet),
        })

    def run_action(self, **overrides):
        env = self.env | overrides
        result = subprocess.run(["bash", str(ACTION)], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        outputs = dict(line.split("=", 1) for line in self.output.read_text().splitlines())
        report = Path(outputs["report-dir"])
        properties = dict(line.split("=", 1) for line in (report / "status.properties").read_text().splitlines())
        self.assertFalse((report / "prompt.txt").exists(), "source packet must be retired")
        return result, outputs, properties

    def arguments(self):
        return json.loads(self.capture.read_text())

    def post_options(self):
        return {"BUILD_AI_PHASE": "post-build", "BUILD_AI_PROMPT_FILE": "",
                "BUILD_AI_BUILD_LOG": str(self.log), "BUILD_AI_BUILD_OUTCOME": "failure"}

    def native_options(self, provider="jni"):
        library = self.root / "native fixture"
        library.write_bytes(b"native fixture")
        library.chmod(0o700)
        model = self.root / "model fixture.gguf"
        model.write_bytes(b"model fixture")
        return {"BUILD_AI_PROVIDER": provider, "BUILD_AI_CHROME_PROFILE": "",
                "BUILD_AI_MODEL": str(model),
                "BUILD_AI_JNI_LIBRARY" if provider == "jni" else "BUILD_AI_LLAMA_BIN": str(library)}

    def test_pre_build_uses_verified_jar_and_existing_cli(self):
        result, outputs, props = self.run_action()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("completed", outputs["status"])
        self.assertEqual("not-run", props["build.outcome"])
        args = self.arguments()["argv"]
        self.assertEqual(["-cp", str(self.jar), "io.synexia.chromellm.standalone.StandaloneMain"], args[:3])
        self.assertEqual("chrome", args[args.index("--backend") + 1])
        self.assertEqual("true", args[args.index("--chrome-download") + 1])
        advice = Path(outputs["advice-file"]).read_bytes()
        self.assertEqual(hashlib.sha256(advice).hexdigest(), props["advice.sha256"])
        self.assertEqual("", result.stdout)

    def test_disabled_never_starts_java_or_requires_jar(self):
        result, outputs, _ = self.run_action(BUILD_AI_ENABLED="false", BUILD_AI_JAR="", BUILD_AI_JAR_SHA256="")
        self.assertEqual(0, result.returncode)
        self.assertEqual("disabled", outputs["status"])
        self.assertFalse(self.capture.exists())

    def test_failed_build_diagnosis_retains_original_outcome(self):
        result, outputs, props = self.run_action(**self.post_options())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("completed", outputs["status"])
        self.assertEqual("failure", props["build.outcome"])
        self.assertIn("Observed build outcome: failure", self.arguments()["prompt"])
        self.assertIn("ExampleTest failed", self.arguments()["prompt"])

    def test_successful_build_is_recorded(self):
        options = self.post_options() | {"BUILD_AI_BUILD_OUTCOME": "success"}
        result, _, props = self.run_action(**options)
        self.assertEqual(0, result.returncode)
        self.assertEqual("success", props["build.outcome"])

    def test_provider_error_preserves_exit_and_has_no_completed_advice(self):
        result, outputs, props = self.run_action(FAKE_EXIT="17")
        self.assertEqual(17, result.returncode)
        self.assertEqual("failed", outputs["status"])
        self.assertEqual("17", props["exit.code"])
        self.assertEqual("", outputs["advice-file"])
        self.assertNotIn("fixture diagnostic", result.stderr)

    def test_digest_mismatch_fails_before_provider(self):
        result, _, _ = self.run_action(BUILD_AI_JAR_SHA256="0" * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_advice_setup_failure_still_records_failed_build(self):
        result, _, props = self.run_action(**(self.post_options() | {"BUILD_AI_JAR_SHA256": "0" * 64}))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("failure", props["build.outcome"])
        self.assertFalse(self.capture.exists())

    def test_shell_metacharacters_stay_in_one_argument(self):
        value = str(self.root / 'chrome;$(touch unexpected) "quoted"')
        result, _, _ = self.run_action(BUILD_AI_CHROME_BIN=value)
        self.assertEqual(0, result.returncode)
        args = self.arguments()["argv"]
        self.assertEqual(value, args[args.index("--chrome-bin") + 1])
        self.assertFalse((ROOT / "unexpected").exists())

    def test_untrusted_advice_is_not_a_workflow_command(self):
        value = "::error::untrusted model text"
        result, outputs, _ = self.run_action(FAKE_ADVICE=value)
        self.assertEqual(0, result.returncode)
        self.assertNotIn(value, result.stdout + result.stderr + self.summary.read_text())
        self.assertIn(value, Path(outputs["advice-file"]).read_text())

    def test_warm_only_chrome_flag_is_forwarded(self):
        result, _, _ = self.run_action(BUILD_AI_ALLOW_DOWNLOAD="false")
        self.assertEqual(0, result.returncode)
        args = self.arguments()["argv"]
        self.assertEqual("false", args[args.index("--chrome-download") + 1])

    def test_invalid_phase_is_refused(self):
        result, _, _ = self.run_action(BUILD_AI_PHASE="post-build\nstatus=completed")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_invalid_provider_is_refused_without_fallback(self):
        result, _, _ = self.run_action(BUILD_AI_PROVIDER="remote")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_skipped_build_is_not_diagnosed_as_success(self):
        result, _, _ = self.run_action(**(self.post_options() | {"BUILD_AI_BUILD_OUTCOME": "skipped"}))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_large_utf8_log_tail_is_bounded_and_marked(self):
        self.log.write_text(("diagnostic café 漢字 " * 9 + "\n") * 400 + "FINAL FAILURE\n", encoding="utf-8")
        result, _, props = self.run_action(**(self.post_options() | {"BUILD_AI_MAX_INPUT_BYTES": "2048"}))
        self.assertEqual(0, result.returncode, result.stderr)
        prompt = self.arguments()["prompt"]
        self.assertLessEqual(len(prompt.encode()), 2048)
        self.assertIn("FINAL FAILURE", prompt)
        self.assertIn("Truncated:", prompt)
        self.assertEqual("true", props["build.log.truncated"])

    def test_single_huge_log_line_cannot_exhaust_packet_budget(self):
        self.log.write_text("é" * 100000, encoding="utf-8")
        result, _, props = self.run_action(**(self.post_options() | {"BUILD_AI_MAX_INPUT_BYTES": "1024"}))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLessEqual(len(self.arguments()["prompt"].encode()), 1024)
        self.assertEqual("true", props["build.log.truncated"])

    def test_oversized_source_packet_is_refused(self):
        self.packet.write_bytes(b"x" * 2049)
        result, _, _ = self.run_action(BUILD_AI_MAX_INPUT_BYTES="2048")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_native_jni_selection_uses_existing_options(self):
        result, _, _ = self.run_action(**self.native_options())
        self.assertEqual(0, result.returncode, result.stderr)
        args = self.arguments()["argv"]
        self.assertEqual("jni", args[args.index("--backend") + 1])
        self.assertIn("--jni-lib", args)
        self.assertNotIn("--chrome-download", args)

    def test_native_cli_selection_uses_existing_options(self):
        result, _, _ = self.run_action(**self.native_options("cli"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--llama-bin", self.arguments()["argv"])

    def test_lazy_native_model_options_are_forwarded(self):
        options = self.native_options() | {"BUILD_AI_MODEL": "", "BUILD_AI_MODEL_URL": "https://models.example.test/licensed.gguf",
                    "BUILD_AI_MODEL_SHA256": "a" * 64, "BUILD_AI_MODEL_CACHE_DIR": str(self.root)}
        result, _, _ = self.run_action(**options)
        self.assertEqual(0, result.returncode, result.stderr)
        args = self.arguments()["argv"]
        self.assertIn("--model-url", args)
        self.assertNotIn("--model", args)

    def test_disabled_native_download_cannot_start_acquisition(self):
        options = self.native_options() | {"BUILD_AI_MODEL": "", "BUILD_AI_MODEL_URL": "https://models.example.test/licensed.gguf",
                    "BUILD_AI_MODEL_SHA256": "a" * 64, "BUILD_AI_MODEL_CACHE_DIR": str(self.root), "BUILD_AI_ALLOW_DOWNLOAD": "false"}
        result, _, _ = self.run_action(**options)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_provider_specific_options_cannot_be_silently_ignored(self):
        result, _, _ = self.run_action(BUILD_AI_MODEL=str(self.jar))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_symlink_jar_is_refused(self):
        link = self.root / "jar-link"
        link.symlink_to(self.jar)
        result, _, _ = self.run_action(BUILD_AI_JAR=str(link))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.capture.exists())

    def test_invalid_budgets_are_refused(self):
        for invalid in ("0", "999999999999999999999", "$(echo 100)", "12.5"):
            with self.subTest(invalid=invalid):
                self.output.unlink(missing_ok=True)
                result, _, _ = self.run_action(BUILD_AI_TIMEOUT_SECONDS=invalid)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.capture.exists())

    def test_cancellation_retires_owned_java_process(self):
        env = self.env | {"FAKE_WAIT": "true"}
        process = subprocess.Popen(["bash", str(ACTION)], env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not self.capture.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(self.capture.exists(), "fixture did not start")
            process.send_signal(signal.SIGTERM)
            _, stderr = process.communicate(timeout=5)
            self.assertEqual(143, process.returncode, stderr)
            self.assertTrue((self.root / "stopped").exists(), "Java child did not receive cancellation")
            with self.assertRaises(ProcessLookupError):
                os.kill(self.arguments()["pid"], 0)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()


if __name__ == "__main__":
    unittest.main(verbosity=2)
