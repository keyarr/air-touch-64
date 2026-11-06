import json
import hashlib
import os
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

from make_bundle import SOURCE_COPIES, SUPPORTED_SWF_INPUT_SHA256, TRANSFORMED_SWF_SHA256, transform_swf
from resource_transforms import apply


class MakeBundleTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("AT64_SOURCE_SWF"), "local SWF fixture not supplied")
    def test_jvm_runner_reconstructs_audited_fws(self):
        source = pathlib.Path(os.environ["AT64_SOURCE_SWF"]).read_bytes()
        self.assertEqual(SUPPORTED_SWF_INPUT_SHA256, hashlib.sha256(source).hexdigest())
        output = transform_swf(source)
        self.assertEqual(b"FWS", output[:3])
        self.assertEqual("496c3e5009b94ebabecf57beb3f5a0dfade967b8ba6e626cadd2446d0bd538c5", hashlib.sha256(output).hexdigest())

    def create_inputs(self, directory, unclassified=False, source_swf=None, final_swf=b"patched swf"):
        source_path = directory / "source.apk"
        base_path = directory / "base.apk"
        final_path = directory / "final.apk"
        source = {
            "assets/TTPixel.swf": source_swf or b"source swf",
            "assets/CABConfig.xml": b"config",
            "classes.dex": b"source dex",
            "assets/resource/application/en/application.zstrings": (
                '"$$$/TTGallery/Adobe Photoshop Touch=Original"\n'
            ).encode("utf-16"),
            "assets/resource/application/about.txt": b"source about\n",
        }
        for source_entry, _ in SOURCE_COPIES:
            source.setdefault(source_entry, ("source:" + source_entry).encode())
        base = {
            "classes.dex": b"base dex",
            "assets/TTPixel.swf": b"source swf",
            "assets/CABConfig.xml": b"base config",
            "AndroidManifest.xml": b"old manifest",
            "resources.arsc": b"old resources",
            "res/icon.png": b"old icon",
            "shell.bin": b"shell",
        }
        final = dict(base)
        final["classes.dex"] = b"patched dex"
        final["AndroidManifest.xml"] = b"PS Touch manifest"
        final["resources.arsc"] = b"PS Touch resources"
        final["res/icon.png"] = b"PS Touch icon"
        final["assets/TTPixel.swf"] = final_swf
        for name, data in source.items():
            if name.startswith("assets/resource/"):
                final[name] = data
        for source_entry, destination in SOURCE_COPIES:
            final[destination] = source[source_entry]
        catalog = "assets/resource/application/en/application.zstrings"
        final[catalog] = apply(catalog, source[catalog])
        final["assets/resource/application/about.txt"] = b"project about\n"
        final["lib/arm64-v8a/project.so"] = b"native"
        if unclassified:
            final["assets/resource/unclassified.bin"] = b"unexpected"
        for path, entries in ((source_path, source), (base_path, base), (final_path, final)):
            with zipfile.ZipFile(path, "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
        return source_path, base_path, final_path

    def run_generator(
        self, directory, unclassified=False, source_swf=None, final_swf=b"patched swf",
        require_local=False, allow_swf_delta=True,
    ):
        source, base, final = self.create_inputs(directory, unclassified, source_swf, final_swf)
        output = directory / "bundle.zip"
        root = pathlib.Path(__file__).resolve().parent.parent
        result = subprocess.run(
            ["python3", "tools/make_bundle.py", "--source", str(source), "--base", str(base),
             "--final", str(final), "--output", str(output)] +
            (["--require-local-swf"] if require_local else []) +
            (["--allow-swf-delta"] if allow_swf_delta and not require_local else []),
            cwd=root, capture_output=True, text=True,
        )
        return result, output

    def test_reconstructs_from_declared_transforms_and_source_copies(self):
        with tempfile.TemporaryDirectory() as temp:
            result, output = self.run_generator(pathlib.Path(temp))
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(output) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                self.assertEqual(["assets/resource/application/about.txt"],
                                 [item["entry"] for item in manifest["resourceOverrides"]])
                self.assertEqual(1, len(manifest["resourceTransforms"]))
                payload = set(archive.namelist())
                self.assertFalse(any(path.startswith("overrides/assets/resource/") and
                                     not path.endswith("about.txt") for path in payload))
                self.assertEqual(10, len(manifest["sourceCopies"]))
                self.assertEqual(b"PS Touch manifest", archive.read("shell/AndroidManifest.xml"))
                self.assertEqual(b"PS Touch resources", archive.read("shell/resources.arsc"))
                self.assertEqual(b"PS Touch icon", archive.read("shell/res/icon.png"))

    def test_rejects_unclassified_resource_differences(self):
        with tempfile.TemporaryDirectory() as temp:
            result, _ = self.run_generator(pathlib.Path(temp), unclassified=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unclassified resource difference", result.stderr)

    @unittest.skipUnless(os.environ.get("AT64_SOURCE_SWF"), "local SWF fixture not supplied")
    def test_new_manifest_uses_local_transform_without_swf_delta(self):
        with tempfile.TemporaryDirectory() as temp:
            source_swf = pathlib.Path(os.environ["AT64_SOURCE_SWF"]).read_bytes()
            final_swf = transform_swf(source_swf)
            result, output = self.run_generator(
                pathlib.Path(temp), source_swf=source_swf, final_swf=final_swf
            )
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(output) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                self.assertEqual(3, manifest["formatVersion"])
                self.assertEqual("local-transform-v1", manifest["swf"]["operation"])
                self.assertNotIn("patch", manifest["swf"])
                self.assertNotIn("patches/swf.bdf", archive.namelist())

    def test_required_local_swf_rejects_unsupported_input_without_output(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            result, output = self.run_generator(directory, require_local=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unsupported SWF input hash", result.stderr)
            self.assertFalse(output.exists())

    def test_generator_rejects_delta_without_explicit_permission(self):
        with tempfile.TemporaryDirectory() as temp:
            result, output = self.run_generator(pathlib.Path(temp), allow_swf_delta=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("pass --allow-swf-delta only for experimental bundles", result.stderr)
            self.assertFalse(output.exists())

    @unittest.skipUnless(os.environ.get("AT64_SOURCE_SWF"), "local SWF fixture not supplied")
    def test_required_local_swf_builds_manifest_v3_without_delta(self):
        with tempfile.TemporaryDirectory() as temp:
            source_swf = pathlib.Path(os.environ["AT64_SOURCE_SWF"]).read_bytes()
            output_swf = transform_swf(source_swf)
            result, output = self.run_generator(
                pathlib.Path(temp), source_swf=source_swf, final_swf=output_swf, require_local=True
            )
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(output) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                self.assertEqual(3, manifest["formatVersion"])
                self.assertEqual("local-transform-v1", manifest["swf"]["operation"])
                self.assertEqual(TRANSFORMED_SWF_SHA256, manifest["swf"]["resultSha256"])
                self.assertNotIn("patches/swf.bdf", archive.namelist())

    @unittest.skipUnless(os.environ.get("AT64_SOURCE_SWF"), "local SWF fixture not supplied")
    def test_required_local_swf_rejects_unexpected_final_output(self):
        with tempfile.TemporaryDirectory() as temp:
            source_swf = pathlib.Path(os.environ["AT64_SOURCE_SWF"]).read_bytes()
            result, output = self.run_generator(
                pathlib.Path(temp), source_swf=source_swf, final_swf=b"unexpected", require_local=True
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("final APK SWF does not match local transformation", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
