#!/usr/bin/env python3
"""Offline Native Messaging protocol and read-only inventory regression tests."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
CLASSES = HERE / "target" / "classes"
PACK = "=I"  # Native endian, standard width, no alignment.


def frame(message):
    if isinstance(message, str):
        message = message.encode("utf-8")
    return struct.pack(PACK, len(message)) + message


def messages_from_bytes(raw):
    offset, result = 0, []
    while offset < len(raw):
        if len(raw) - offset < 4:
            raise AssertionError("truncated response header")
        size = struct.unpack_from(PACK, raw, offset)[0]
        offset += 4
        if len(raw) - offset < size:
            raise AssertionError("truncated response body")
        result.append(json.loads(raw[offset:offset + size].decode("utf-8")))
        offset += size
    return result


class HostTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "approved"
        self.root.mkdir()
        (self.root / "standalone").mkdir()
        (self.root / "README.md").write_bytes(b"verified contract sample\n")
        (self.root / "standalone" / "pom.xml").write_bytes(b"<project/>\n")
        self.config = Path(self.tmp.name) / "config.properties"
        self.config.write_text("repo.ChromeLLM=" + self.root.as_posix()
                               + "\nagent.allowed_origin=chrome-extension://" + "a" * 32
                               + "/\n", encoding="utf-8")

    def run_host(self, *frames, launch_args=()):
        proc = subprocess.run(
            ["java", "-cp", str(CLASSES), "io.synexia.chromellm.webmcp.AgentHost", str(self.config), *launch_args],
            input=b"".join(frames), capture_output=True, timeout=10,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr.decode("utf-8", errors="replace"))
        self.assertEqual(proc.stderr, b"")
        return messages_from_bytes(proc.stdout)

    def test_hello_inventory_hash_and_replay(self):
        before = (self.root / "README.md").read_bytes()
        msgs = self.run_host(frame('{"op":"hello"}'), frame('{"op":"inventory","repo":"ChromeLLM"}'), frame('{"op":"inventory","repo":"ChromeLLM"}'))
        self.assertEqual(msgs[0], {"ok": True, "protocol": "m3-native-v0", "access": "read-only"})
        self.assertEqual(msgs[1], msgs[2])
        self.assertTrue(msgs[1]["ok"])
        sha = hashlib.sha256(before).hexdigest()
        self.assertIn({"path": "README.md", "bytes": len(before), "sha256": sha}, msgs[1]["files"])
        self.assertEqual((self.root / "README.md").read_bytes(), before)

    def test_bad_messages_rejected(self):
        examples = [
            '{"op":"delete"}',
            '{"op":"inventory","repo":"../../etc"}',
            '{"op":"inventory","repo":"ChromeLLM","path":"/etc/passwd"}',
            '{"op":"hello","op":"inventory"}',
            '{"op":23}',
            '{"op":"hello",}',
            '{"op":"hello"} trailing',
            '{"op":"hello","nested":{}}',
        ]
        replies = self.run_host(*[frame(value) for value in examples])
        self.assertEqual(len(replies), len(examples))
        self.assertTrue(all(reply == {"ok": False, "error": "INVALID_REQUEST"} for reply in replies))

    def test_malformed_utf8_and_oversize(self):
        bad_utf8 = self.run_host(frame(b'\xff\xfe'))
        self.assertEqual(bad_utf8, [{"ok": False, "error": "INVALID_FRAME"}])
        oversized = self.run_host(struct.pack(PACK, 1024 * 1024 + 1))
        self.assertEqual(oversized, [{"ok": False, "error": "INVALID_FRAME"}])

    def test_symlink_is_not_read(self):
        secret = Path(self.tmp.name) / "secret.txt"
        secret.write_bytes(b"secret\n")
        (self.root / "README.md").unlink()
        (self.root / "README.md").symlink_to(secret)
        response = self.run_host(frame('{"op":"inventory","repo":"ChromeLLM"}'))[0]
        self.assertTrue(response["ok"])
        self.assertNotIn("README.md", [x["path"] for x in response["files"]])
        self.assertEqual(secret.read_bytes(), b"secret\n")

    def test_chrome_launcher_origin_and_window_are_admitted(self):
        origin = "chrome-extension://" + "a" * 32
        messages = self.run_host(frame('{"op":"hello"}'), launch_args=(origin,))
        self.assertEqual(messages[0]["access"], "read-only")
        with_window = self.run_host(frame('{"op":"hello"}'),
                                    launch_args=(origin + "/", "--parent-window=0"))
        self.assertEqual(messages, with_window)

    def test_packaged_jvm_config_admits_chrome_arguments(self):
        origin = "chrome-extension://" + "a" * 32
        command = ["java", "-Dchromellm.agent.config=" + str(self.config),
                   "-cp", str(CLASSES), "io.synexia.chromellm.webmcp.AgentHost"]
        request = frame('{"op":"hello"}')
        allowed = subprocess.run(command + [origin, "--parent-window=0"],
                                 input=request, capture_output=True, timeout=10, check=False)
        self.assertEqual(allowed.returncode, 0, allowed.stderr)
        self.assertEqual(messages_from_bytes(allowed.stdout)[0]["access"], "read-only")
        # The property is authoritative. A second positional config is not accepted.
        invalid = subprocess.run(command + [str(self.config), origin],
                                 input=request, capture_output=True, timeout=10, check=False)
        self.assertEqual(invalid.returncode, 2)
        self.assertEqual(invalid.stdout, b"")
        denied = subprocess.run(command + ["chrome-extension://" + "b" * 32],
                                input=request, capture_output=True, timeout=10, check=False)
        self.assertEqual(denied.returncode, 2)
        self.assertEqual(denied.stdout, b"")

    def test_chrome_launcher_denies_bad_or_mismatched_origin(self):
        origin = "chrome-extension://" + "a" * 32
        examples = [
            ("chrome-extension://" + "b" * 32,),
            ("chrome-extension://" + "a" * 31,),
            ("chrome-extension://" + "a" * 32 + "/evil",),
            ("--parent-window=0",),
            (origin, origin),
            (origin, "--parent-window=-1"),
            (origin, "--parent-window=0", "--parent-window=1"),
            (origin, "--unexpected=1"),
        ]
        for args in examples:
            with self.subTest(args=args):
                proc = subprocess.run(
                    ["java", "-cp", str(CLASSES), "io.synexia.chromellm.webmcp.AgentHost", str(self.config), *args],
                    input=frame('{"op":"inventory","repo":"ChromeLLM"}'),
                    capture_output=True, timeout=10, check=False)
                self.assertEqual(proc.returncode, 2)
                self.assertEqual(proc.stdout, b"")

    def test_unconfigured_repo_has_explicit_error(self):
        response = self.run_host(frame('{"op":"inventory","repo":"dawn"}'))[0]
        self.assertEqual(response, {"ok": False, "error": "REPO_NOT_CONFIGURED"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
