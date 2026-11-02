#!/usr/bin/env python3
"""Apply the navigation-bar touch offset fix to the DEX patch carried by the bundle.

The shipped bundle patches the AIR runtime DEX with a bsdiff delta. This tool regenerates that
delta so the generated PS Touch APK keeps the canvas aligned with touch input when Android's
navigation bar is visible.

Background: AIR's ``AIRWindowSurfaceView.surfaceChanged()`` treats every same-orientation height
shrink as a soft keyboard resize. It queues ``nativePerformWindowPanning(delta)``, marks the
surface as changed for the soft keyboard and returns before updating ``mHt`` or sending the
normal size-changed event. When the navigation bar appears the surface shrinks by exactly that
bar, so the Stage stays on the taller size while the surface is shorter and the drawing ends up
offset from the touch point. The patch asks ``WindowInsets`` for the IME inset first and only
takes the keyboard branch when an IME is really visible, otherwise it falls through to the normal
resize path. Below API 30 the previous behaviour is kept.

The canonical copy of this transformation lives in the PS Touch Revived repository at
``experiments/ttpixel-arm64-test/patch-android-insets.py``; keep both in sync.

Requires baksmali/smali and bsdiff4:

    python3 tools/patch_bundle_dex.py bundle.at64 --source source.apk \
        --baksmali "$AIR_SDK/lib/android/lib/baksmali.jar" \
        --smali "$AIR_SDK/lib/android/lib/smali.jar"
"""

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

import bsdiff4
from encrypt_bundle import decrypt_bytes, encrypt_bytes

SURFACE_VIEW = "com/adobe/air/AIRWindowSurfaceView.smali"
SURFACE_CHANGED_MARKER = "->getRootWindowInsets()"
API_LEVEL = "34"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def resolve_jar(argument: pathlib.Path | None, env_var: str, rel_path: str) -> pathlib.Path:
    if argument and argument.is_file():
        return argument
    base = os.environ.get(env_var)
    if base:
        candidate = pathlib.Path(base) / rel_path
        if candidate.is_file():
            return candidate
    raise SystemExit(f"Could not locate {rel_path}. Pass it explicitly or set {env_var}.")


def patch_surface_changed_smali(smali_text: str) -> str:
    """Only treat a height shrink as a soft keyboard resize when the IME is visible."""
    if SURFACE_CHANGED_MARKER in smali_text:
        return smali_text

    method = re.search(
        r"\.method public surfaceChanged\(Landroid/view/SurfaceHolder;III\)V\n"
        r"    \.registers (\d+)\n"
        r"(.*?)"
        r"\.end method",
        smali_text,
        re.S,
    )
    if not method:
        raise SystemExit("surfaceChanged() not found in AIRWindowSurfaceView")

    registers = int(method.group(1))
    body = method.group(2)

    shrink = re.search(
        r"iget (?P<height>v\d+), p0, Lcom/adobe/air/AIRWindowSurfaceView;->mHt:I\s+"
        r"if-ge p4, (?P=height), :(?P<normal>\w+)\s+"
        r"if-nez p4, :(?P<zero>\w+)\s+"
        r"goto(?:/16)? :(\w+)\s+"
        r":(?P=zero)\s+"
        r"sub-int/2addr (?P=height), p4",
        body,
    )
    if not shrink:
        raise SystemExit("soft keyboard shrink branch not found in surfaceChanged()")

    pan_return = re.search(
        r"iput-boolean \w+, p0, "
        r"Lcom/adobe/air/AIRWindowSurfaceView;->mSurfaceChangedForSoftKeyboard:Z\s+return-void",
        body[shrink.end():],
    )
    if not pan_return:
        raise SystemExit("panning block not found in surfaceChanged()")

    first_local = registers - 5  # this + SurfaceHolder + three ints
    scratch, inset_type = f"v{first_local}", f"v{first_local + 1}"
    if first_local + 1 > 255:
        raise SystemExit("surfaceChanged() has no spare registers for the guard")

    guard = (
        f"\n    sget {scratch}, Landroid/os/Build$VERSION;->SDK_INT:I\n"
        f"\n    const/16 {inset_type}, 0x1e\n"
        f"\n    if-ge {scratch}, {inset_type}, :pscc_use_insets\n"
        f"\n    goto/16 :pscc_ime_present\n"
        f"\n    :pscc_use_insets\n"
        f"    invoke-virtual {{p0}}, Landroid/view/View;->getRootWindowInsets()Landroid/view/WindowInsets;\n"
        f"\n    move-result-object {scratch}\n"
        f"\n    if-eqz {scratch}, :pscc_ime_present\n"
        f"\n    const/16 {inset_type}, 0x8\n"
        f"\n    invoke-virtual {{{scratch}, {inset_type}}}, Landroid/view/WindowInsets;->getInsets(I)Landroid/graphics/Insets;\n"
        f"\n    move-result-object {scratch}\n"
        f"\n    iget {scratch}, {scratch}, Landroid/graphics/Insets;->bottom:I\n"
        f"\n    if-gtz {scratch}, :pscc_ime_present\n"
        f"\n    goto/16 :pscc_ime_absent\n"
        f"\n    :pscc_ime_present"
    )
    bypass = f"\n    :pscc_ime_absent\n    goto/16 :{shrink.group('normal')}\n"

    matched = body[shrink.start():shrink.end()]
    label = "\n    :" + shrink.group("zero")
    label_end = matched.index(label) + len(label)
    head, tail = matched[:label_end], matched[label_end:]
    patched_body = body[:shrink.start()] + head + guard + tail + body[shrink.end():]

    pan_end = shrink.start() + len(head) + len(guard) + len(tail) + pan_return.end()
    patched_body = patched_body[:pan_end] + bypass + patched_body[pan_end:]

    patched = (
        ".method public surfaceChanged(Landroid/view/SurfaceHolder;III)V\n"
        f"    .registers {registers + 2}\n" + patched_body + ".end method"
    )
    return smali_text[:method.start()] + patched + smali_text[method.end():]


def disassemble(baksmali: pathlib.Path, dex: pathlib.Path, out: pathlib.Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["java", "-jar", str(baksmali), "disassemble", str(dex), "-o", str(out)],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def assemble(smali: pathlib.Path, source: pathlib.Path, out: pathlib.Path) -> None:
    subprocess.run(
        ["java", "-jar", str(smali), "assemble", "-a", API_LEVEL, str(source), "-o", str(out)],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=pathlib.Path, help="input ZIP or encrypted AT64 bundle")
    parser.add_argument("--baksmali", type=pathlib.Path, default=None)
    parser.add_argument("--smali", type=pathlib.Path, default=None)
    parser.add_argument("--output", type=pathlib.Path, default=None, help="defaults to rewriting the bundle")
    parser.add_argument("--source", type=pathlib.Path, help="source APK required for AT64 input/output")
    args = parser.parse_args()

    baksmali = resolve_jar(args.baksmali, "AIR_SDK", "lib/android/lib/baksmali.jar")
    smali = resolve_jar(args.smali, "AIR_SDK", "lib/android/lib/smali.jar")

    raw_bundle = args.bundle.read_bytes()
    encrypted = raw_bundle.startswith(b"AT64")
    if encrypted:
        if not args.source:
            raise SystemExit("--source is required for AT64 bundles")
        raw_bundle = decrypt_bytes(args.source, raw_bundle)
    import io
    with zipfile.ZipFile(io.BytesIO(raw_bundle)) as archive:
        entries = [(info, archive.read(info.filename)) for info in archive.infolist()]
    manifest_entry, manifest_raw = next((info, data) for info, data in entries if info.filename == "manifest.json")
    manifest = json.loads(manifest_raw)
    if len(manifest["dexPatches"]) != 1:
        raise SystemExit(f"expected one DEX patch, found {len(manifest['dexPatches'])}")
    dex_patch = manifest["dexPatches"][0]
    patch_name = dex_patch["patch"]
    shell_name = f"shell/{dex_patch['entry']}"

    payload = dict((info.filename, data) for info, data in entries)
    shell_dex = payload[shell_name]
    if sha256(shell_dex) != dex_patch["inputSha256"]:
        raise SystemExit(f"{shell_name} does not match the manifest input hash")
    current_dex = bsdiff4.patch(shell_dex, payload[patch_name])
    if sha256(current_dex) != dex_patch["outputSha256"]:
        raise SystemExit(f"{patch_name} does not reproduce the manifest output hash")

    with tempfile.TemporaryDirectory(prefix="airtouch-dex-") as temporary:
        work = pathlib.Path(temporary)
        current = work / dex_patch["entry"]
        current.write_bytes(current_dex)
        tree = work / "smali"
        disassemble(baksmali, current, tree)

        surface_view = tree / SURFACE_VIEW
        if not surface_view.is_file():
            raise SystemExit(f"{SURFACE_VIEW} not found in {dex_patch['entry']}")
        original_text = surface_view.read_text()
        fixed_text = patch_surface_changed_smali(original_text)
        if fixed_text == original_text:
            print("bundle already carries the navigation bar fix; nothing to do")
            return

        surface_view.write_text(fixed_text)
        fixed_dex = work / f"fixed-{dex_patch['entry']}"
        assemble(smali, tree, fixed_dex)

        # The patched tree must be stable: disassembling the new DEX and patching it again
        # has to be a no-op, and only AIRWindowSurfaceView may differ from the input.
        check_tree = work / "smali-check"
        disassemble(baksmali, fixed_dex, check_tree)
        if patch_surface_changed_smali((check_tree / SURFACE_VIEW).read_text()) != (check_tree / SURFACE_VIEW).read_text():
            raise SystemExit("re-assembled DEX still needs the fix")
        changed = [
            path.relative_to(tree).as_posix()
            for path in tree.rglob("*.smali")
            if path.relative_to(tree).as_posix() != SURFACE_VIEW
            and (check_tree / path.relative_to(tree)).read_bytes() != path.read_bytes()
        ]
        if changed:
            raise SystemExit(f"re-assembly changed unrelated classes: {sorted(changed)[:5]}")

        fixed_bytes = fixed_dex.read_bytes()
        delta = bsdiff4.diff(shell_dex, fixed_bytes)
        if bsdiff4.patch(shell_dex, delta) != fixed_bytes:
            raise SystemExit("regenerated delta does not reproduce the patched DEX")

    dex_patch["outputSha256"] = sha256(fixed_bytes)
    updated_manifest = json.dumps(manifest, indent=2).encode()
    output = args.output or args.bundle

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for info, data in entries:
            if info.filename == "manifest.json":
                data = updated_manifest
            elif info.filename == patch_name:
                data = delta
            archive.writestr(info.filename, data)
    result = buffer.getvalue()
    if output.suffix.lower() in {".at64", ".enc"} or (encrypted and args.output is None):
        if not args.source:
            raise SystemExit("--source is required to encrypt AT64 output")
        output.write_bytes(encrypt_bytes(args.source, result))
    else:
        output.write_bytes(result)

    print(f"patched {output}")
    print(f"  {dex_patch['entry']}: {sha256(current_dex)} -> {sha256(fixed_bytes)}")
    print(f"  {patch_name}: {len(payload[patch_name])} -> {len(delta)} bytes")
    print(f"  manifest output hash updated")


if __name__ == "__main__":
    sys.exit(main())
