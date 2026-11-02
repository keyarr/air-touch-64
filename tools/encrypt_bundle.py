#!/usr/bin/env python3
"""Encrypt a patch ZIP as an AT64 bundle using content-derived source APK key material."""
import argparse
import hashlib
import json
import pathlib
import secrets
import struct
import zipfile

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"AT64"
VERSION = 2


def encrypt_bytes(source: pathlib.Path, archive_bytes: bytes) -> bytes:
    import io

    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        manifest = json.loads(archive.read("manifest.json"))
    source_spec = manifest["source"]
    digest = hashlib.sha512()
    with zipfile.ZipFile(source) as archive:
        for name in (source_spec["swfEntry"], "assets/CABConfig.xml", "classes.dex"):
            data = archive.read(name)
            digest.update(struct.pack(">Q", len(data)))
            digest.update(data)
    salt, nonce = secrets.token_bytes(16), secrets.token_bytes(12)
    password = digest.hexdigest().encode("ascii")
    key = hashlib.pbkdf2_hmac("sha256", password, salt, 120_000, dklen=32)
    header = json.dumps({key: manifest[key] for key in ("formatVersion", "name", "version", "source", "steps") if key in manifest}, separators=(",", ":"), ensure_ascii=False).encode()
    header = header.replace(b'"formatVersion":1', b'"formatVersion":2', 1)
    ciphertext = AESGCM(key).encrypt(nonce, archive_bytes, header)
    return MAGIC + struct.pack(">I", VERSION) + salt + nonce + struct.pack(">I", len(header)) + header + ciphertext


def decrypt_bytes(source: pathlib.Path, bundle_bytes: bytes) -> bytes:
    offset = 4
    if bundle_bytes[:4] != MAGIC or struct.unpack_from(">I", bundle_bytes, offset)[0] != VERSION:
        raise ValueError("invalid AT64 bundle")
    offset += 4
    salt, nonce = bundle_bytes[offset:offset + 16], bundle_bytes[offset + 16:offset + 28]
    offset += 28
    header_length = struct.unpack_from(">I", bundle_bytes, offset)[0]
    offset += 4
    header = bundle_bytes[offset:offset + header_length]
    offset += header_length
    with zipfile.ZipFile(source) as archive:
        header_data = json.loads(header)
        digest = hashlib.sha512()
        for name in (header_data["source"]["swfEntry"], "assets/CABConfig.xml", "classes.dex"):
            data = archive.read(name)
            digest.update(struct.pack(">Q", len(data)))
            digest.update(data)
    key = hashlib.pbkdf2_hmac("sha256", digest.hexdigest().encode("ascii"), salt, 120_000, dklen=32)
    return AESGCM(key).decrypt(nonce, bundle_bytes[offset:], header)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--input", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encrypt_bytes(args.source, args.input.read_bytes()))
    print(f"encrypted bundle: {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
