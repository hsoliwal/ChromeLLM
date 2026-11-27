# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
"""Offline checks that the native smoke fixture submits the bytes it hashes."""

import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(os.environ.get("CHROMELLM_LAZY_SMOKE_SOURCE",
                             Path(__file__).with_name("lazy_runtime_smoke.py")))
SPEC = importlib.util.spec_from_file_location("lazy_runtime_smoke", SOURCE)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


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
