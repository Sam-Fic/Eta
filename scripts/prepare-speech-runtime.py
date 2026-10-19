#!/usr/bin/env python3
"""Pinned upstream Android JNI binaries. No model weights in the APK."""
import hashlib
import io
import os
from pathlib import Path
import sys
import tarfile
import urllib.request

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')

# Two engines coexist so the user can pick a model without a rebuild:
# sherpa-ncnn   -> ncnn/pnnx Streaming Zipformer transducer (2023 bilingual pack);
# sherpa-onnx   -> ONNX Runtime, used by OnlineRecognizer (Streaming Zipformer2,
#                  e.g. X-ASR-zh-en chunk-480ms).
# Each entry is (url, sha256, exact size, library file names).
RUNTIMES = (
    (
        'https://github.com/k2-fsa/sherpa-ncnn/releases/download/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2',
        '7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65',
        9820071,
        ('libsherpa-ncnn-jni.so', 'libncnn.so'),
    ),
    (
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2',
        '2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44',
        46093321,
        ('libsherpa-onnx-jni.so', 'libonnxruntime.so'),
    ),
)

# A single ABI's JNI library is far below this; guards a corrupted member header.
MAX_LIBRARY_BYTES = 64 * 1024 * 1024


def find_member(archive, abi, name):
    """Match by path suffix; the archive root prefix is not part of the contract."""
    suffix = f'jniLibs/{abi}/{name}'
    matches = [
        member for member in archive.getmembers()
        if member.isfile() and member.name.endswith(suffix)
    ]
    if len(matches) != 1:
        raise RuntimeError(f'Expected exactly one archive member ending with {suffix}')
    return matches[0]


def prepare(output):
    for url, sha256, size, libraries in RUNTIMES:
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read(size + 1)
        if len(data) != size:
            raise RuntimeError('Speech runtime size mismatch')
        if hashlib.sha256(data).hexdigest() != sha256:
            raise RuntimeError('Speech runtime SHA-256 mismatch')
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as archive:
            for abi in ABIS:
                for name in libraries:
                    member = find_member(archive, abi, name)
                    if member.size > MAX_LIBRARY_BYTES:
                        raise RuntimeError('Oversized runtime member')
                    target = output / abi / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    tmp = target.with_suffix('.tmp')
                    tmp.write_bytes(archive.extractfile(member).read())
                    os.replace(tmp, target)


if __name__ == '__main__':
    prepare(Path(sys.argv[1]))
