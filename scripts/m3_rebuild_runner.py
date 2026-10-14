#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pinned, explicit ChromeML runner rebuild; never reads Chrome model weights."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def filehash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_plan(root, zig):
    source = root / 'runtime' / 'ChromeMLBareRunner.cpp'
    if not source.is_file() or source.is_symlink():
        raise ValueError('explicit C++ runner source required')
    target = root / 'target' / 'm3-chrome-rebuild' / 'ChromeMLBareRunner.exe'
    command = [zig, 'c++', '-target', 'x86_64-windows-gnu', '-std=c++17',
               '-O2', '-municode', '-I' + str(root / 'runtime'),
               str(source), '-o', str(target), '-lole32', '-lwindowscodecs', '-lpsapi']
    return source, target, command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--zig', default='zig')
    parser.add_argument('--build', action='store_true', help='explicit opt-in native rebuild')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    source, target, command = build_plan(root, args.zig)
    source_sha = filehash(source)
    if not args.build:
        print(json.dumps({'status': 'PLAN_ONLY', 'source_sha256': source_sha,
                          'command': command, 'weights_used': False}, sort_keys=True))
        return
    if shutil.which(args.zig) is None and not Path(args.zig).is_file():
        raise FileNotFoundError('Zig compiler not installed')
    target.parent.mkdir(parents=True, exist_ok=True)
    compiler_version = subprocess.run([args.zig, 'version'], check=True, capture_output=True,
                                      text=True, timeout=20).stdout.strip()
    result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=300)
    if not target.is_file():
        raise RuntimeError('Zig exited successfully but produced no runner')
    if filehash(source) != source_sha:
        raise RuntimeError('C++ source changed during rebuild')
    receipt = {'status': 'SOURCE_COMPILED_UNTESTED_CHROME_RUNTIME',
               'source_sha256': source_sha, 'output_sha256': filehash(target),
               'output_bytes': target.stat().st_size, 'zig_version': compiler_version,
               'command': command, 'weights_used': False,
               'chrome_runtime_test': 'NOT_RUN', 'model_redistributed': False}
    (target.parent / 'BUILD_RECEIPT.json').write_text(json.dumps(receipt, indent=2, sort_keys=True)+'\n')
    print(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    main()
