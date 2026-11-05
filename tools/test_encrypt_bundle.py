import io
import struct
import json
import pathlib
import tempfile
import unittest
import zipfile

from encrypt_bundle import decrypt_bytes, encrypt_bytes


class EncryptBundleTest(unittest.TestCase):
    def test_encrypted_bundle_round_trip_and_source_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source.apk"
            with zipfile.ZipFile(source, "w") as archive:
                for name, data in {
                    "assets/TTPixel.swf": b"swf bytes",
                    "assets/CABConfig.xml": b"config",
                    "classes.dex": b"dex bytes",
                }.items():
                    archive.writestr(name, data)
            output = io.BytesIO()
            manifest = {
                "formatVersion": 2,
                "name": "sample",
                "version": "1",
                "source": {
                    "sha256": ["0" * 64],
                    "requiredEntries": ["assets/TTPixel.swf", "assets/CABConfig.xml"],
                    "swfEntry": "assets/TTPixel.swf",
                },
                "swf": {"patch": "patches/swf.bdf", "sourceSha256": "a", "resultSha256": "b"},
                "steps": [],
            }
            with zipfile.ZipFile(output, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
                archive.writestr("patches/swf.bdf", b"payload")
            encrypted = encrypt_bytes(source, output.getvalue())
            self.assertEqual(b"AT64", encrypted[:4])
            self.assertEqual(output.getvalue(), decrypt_bytes(source, encrypted))
            wrong = pathlib.Path(directory) / "wrong.apk"
            with zipfile.ZipFile(wrong, "w") as archive:
                archive.writestr("assets/TTPixel.swf", b"wrong")
                archive.writestr("assets/CABConfig.xml", b"config")
                archive.writestr("classes.dex", b"dex bytes")
            with self.assertRaises(Exception):
                decrypt_bytes(wrong, encrypted)

    def test_transform_manifest_uses_at64_format_four(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source.apk"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("assets/TTPixel.swf", b"swf bytes")
                archive.writestr("assets/CABConfig.xml", b"config")
                archive.writestr("classes.dex", b"dex bytes")
            raw = io.BytesIO()
            manifest = {
                "formatVersion": 3,
                "name": "transform",
                "version": "1",
                "source": {
                    "sha256": ["0" * 64],
                    "requiredEntries": ["assets/TTPixel.swf", "assets/CABConfig.xml"],
                    "swfEntry": "assets/TTPixel.swf",
                },
                "swf": {
                    "operation": "local-transform-v1",
                    "sourceSha256": "input",
                    "resultSha256": "output",
                },
                "steps": [],
            }
            with zipfile.ZipFile(raw, "w") as archive:
                archive.writestr("manifest.json", json.dumps(manifest))
            encrypted = encrypt_bytes(source, raw.getvalue())
            self.assertEqual(4, struct.unpack_from(">I", encrypted, 4)[0])
            with zipfile.ZipFile(io.BytesIO(decrypt_bytes(source, encrypted))) as archive:
                self.assertEqual(manifest, json.loads(archive.read("manifest.json")))


if __name__ == "__main__":
    unittest.main()
