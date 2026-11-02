import io
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
                "formatVersion": 1,
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


if __name__ == "__main__":
    unittest.main()
