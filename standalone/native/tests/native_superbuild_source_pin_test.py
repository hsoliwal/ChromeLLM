# SPDX-License-Identifier: MIT
"""Offline regression checks for source pinning in the native superbuild."""
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

class NativeSourcePinTest(unittest.TestCase):
    def setUp(self):
        for tool in ("git", "cmake"):
            if not shutil.which(tool):
                self.skipTest(tool + " not installed")
        self.temp = tempfile.TemporaryDirectory(prefix="chromellm-pin-")
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.source = self.home / "source"
        self.source.mkdir()
        path = Path(os.environ.get("CHROMELLM_PIN_SOURCE_FILE",
                    Path(__file__).resolve().parents[2] / "native-superbuild/CMakeLists.txt"))
        contents = path.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^function\(chromellm_source .*?^endfunction\(\)", contents)
        self.assertIsNotNone(match, "chromellm_source is missing")
        self.fixture = self.home / "fixture"
        self.fixture.mkdir()
        dollar = "$"
        (self.fixture / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.22)\n"
            "project(native_pin_fixture LANGUAGES NONE)\n"
            "include(ExternalProject)\n" + match.group(0) + "\n"
            'chromellm_source(llama "https://invalid.invalid/native" "'
            + dollar + '{TEST_REVISION}" "' + dollar
            + '{TEST_SOURCE_DIR}" actual dependency)\n', encoding="utf-8")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args],
                                       text=True).strip()

    def checkout(self):
        self.git("init", "-q")
        (self.source / "CMakeLists.txt").write_text("cmake_minimum_required(VERSION 3.22)\n")
        self.git("add", "CMakeLists.txt")
        self.git("-c", "user.name=Native Pin Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "initial")
        return self.git("rev-parse", "HEAD")

    def configure(self, revision):
        build = self.home / "build"
        return subprocess.run(["cmake", "-S", str(self.fixture), "-B", str(build),
            "-DTEST_REVISION=" + revision, "-DTEST_SOURCE_DIR=" + str(self.source)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def test_exact_head(self):
        result = self.configure(self.checkout())
        self.assertEqual(0, result.returncode, result.stdout)

    def test_changed_head_rejected(self):
        old = self.checkout()
        (self.source / "CMakeLists.txt").write_text("cmake_minimum_required(VERSION 3.31)\n")
        self.git("add", "CMakeLists.txt")
        self.git("-c", "user.name=Native Pin Test", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "changed")
        result = self.configure(old)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("source HEAD does not match", result.stdout)

    def test_non_git_checkout_rejected(self):
        (self.source / "CMakeLists.txt").write_text("cmake_minimum_required(VERSION 3.22)\n")
        result = self.configure("0" * 40)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("source HEAD does not match", result.stdout)

    def test_symbolic_revision_rejected(self):
        self.checkout()
        result = self.configure("main")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("requires an immutable 40-character Git commit", result.stdout)

if __name__ == "__main__":
    unittest.main()
