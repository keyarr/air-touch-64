"""Deterministic transformations for the bundled resource manifest."""
import html
import re
from shader_blocks import SHADER_MAIN

CATALOG_ENTRY = re.compile(r"assets/resource/application/(de|en|es|fr|it|ja|pt|ru)/application\.zstrings$")
ABOUT_ENTRY = "assets/resource/application/gallery/ttgallerysettingsdetailpanelmodule.cabmod"
SHADER_DIR = "assets/resource/application/filters/"
FACEBOOK = '"$$$/private/GoURL/FacebookPage=https://github.com/keyarr/air-touch-64"\n'


def sha256(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


def _catalog(data: bytes, locale: str) -> bytes:
    if data.startswith(b"\xff\xfe"):
        encoding = "utf-16-le"
    elif data.startswith(b"\xfe\xff"):
        encoding = "utf-16-be"
    else:
        raise ValueError("catalog has no UTF-16 BOM")
    text = data[2:].decode(encoding)
    edits = {"$$$/TTGallery/Adobe Photoshop Touch": "Ps Touch"}
    if locale == "ja":
        edits.update({
            "$$$/about/window/title/About": "About",
            "$$$/TTGallerySettingsPanelModule/About": "About",
            "$$$/ttgallerysettingspanelmodule/text/text/About": "About",
        })
    lines = text.splitlines(keepends=True)
    for key, value in edits.items():
        found = 0
        for i, line in enumerate(lines):
            if line.startswith('"' + key + "=") and line.rstrip("\r\n").endswith('"'):
                ending = "\r\n" if line.endswith("\r\n") else "\n" if line.endswith("\n") else "\r" if line.endswith("\r") else ""
                lines[i] = '"' + key + "=" + value + '"' + ending
                found += 1
        if found != 1:
            raise ValueError(f"{key}: expected one catalog key, found {found}")
    marker = '"$$$/private/GoURL/FacebookPage='
    has_facebook = marker in text
    if not has_facebook:
        text += FACEBOOK
    return data[:2] + ("".join(lines) + ("" if has_facebook else FACEBOOK)).encode(encoding)


def _about(data: bytes) -> bytes:
    text = data.decode("utf-8")

    def update(element_id: str, attrs: dict[str, str]) -> None:
        nonlocal text
        pattern = re.compile(rf'<(?:text|ttactiontext)\b(?=[^>]*\bid="{re.escape(element_id)}")[^>]*/>')
        match = pattern.search(text)
        if not match:
            raise ValueError(f"missing About element {element_id}")
        element = match.group()
        for name, value in attrs.items():
            replacement = f'{name}="{html.escape(value, quote=True)}"'
            attr = re.compile(rf'\b{re.escape(name)}="[^"]*"')
            element, count = attr.subn(replacement, element, count=1)
            if not count:
                element = element[:-2] + " " + replacement + "/>"
        text = text[:match.start()] + element + text[match.end():]

    def remove(element_id: str) -> None:
        nonlocal text
        pattern = re.compile(rf'<text\b(?=[^>]*\bid="{re.escape(element_id)}")[^>]*/>\s*')
        text, count = pattern.subn("", text)
        if count != 1:
            raise ValueError(f"{element_id}: expected one removed About element, found {count}")

    def rename(old: str, new: str) -> None:
        nonlocal text
        pattern = re.compile(rf'(<(?:text|ttactiontext)\b(?=[^>]*\bid="{re.escape(old)}")[^>]*\bid=")[^"]+("[^>]*/>)')
        text, count = pattern.subn(rf'\g<1>{new}\2', text)
        if count != 1:
            raise ValueError(f"{old}: expected one About id, found {count}")

    update("text2locprivate", {"text": "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Adobe, the Adobe logo, and Photoshop are either registered trademarks or trademarks of Adobe Systems Incorporated in the United States and/or other countries.=Adobe, the Adobe logo, and Photoshop are either registered trademarks or trademarks of Adobe Systems Incorporated in the United States and/or other countries."})
    update("ttboldtext112ttlargetextlocprivate", {"text": "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Adobe#{reg} Photoshop#{reg} Touch for Phone=Ps Touch"})
    update("text22locprivate", {"text": "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Copyright #{copy} 2015 Adobe Systems Incorporated and its licensors. All rights reserved.=Copyright © 2015 Adobe Systems Incorporated and its licensors. All rights reserved."})
    update("text3locprivate", {"text": "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Notices, terms and conditions pertaining to third party software are located at =Notices, terms and conditions pertaining to third-party software are available at:"})
    update("text11", {"text": "$$$/private/ttgallerysettingsdetailpanelmodule/text/text/Third-party notices=Third-party notices", "target": "referenceDispatcher", "selector": "thirdParty"})
    remove("text321locprivate")
    remove("text322locprivate")
    if re.search(r'<(?:text|ttactiontext)\b(?=[^>]*\bid="text31ttlargetext")', text):
        rename("text31ttlargetext", "text31")
    update("text31", {"text": "$$$/ttgallerysettingsdetailpanelmodule/text/text/Project source=Project source:"})
    update("text111", {"text": "$$$/ttgallerysettingsdetailpanelmodule/text/text/GitHub repository=GitHub repository", "selector": "facebookPage"})
    return text.encode("utf-8")


def _shader(entry: str, data: bytes) -> bytes:
    name = entry.removeprefix(SHADER_DIR)
    text = data.decode("utf-8")
    if text.count("void main()") != 1:
        raise ValueError(f"{entry}: expected one main function")
    text = text.replace("/*$$$ \n", "/*$$$\n", 1)
    if name == "posterize.fs":
        text = text.replace('min="1" max="32"', 'min="2" max="32"', 1)
        text = re.sub(r"uniform vec4 color1;\nuniform vec4 color2;\n\n", "", text, count=1)
        start = text.index("precision highp float;")
        end = text.index("varying vec2 mainTexCoord;")
        declaration = text[start:end]
        declaration = re.sub(r"(?<!precision highp float;)\n\n(?=uniform)", "\n", declaration)
        text = text[:start] + declaration + text[end:]
        text = text.replace("uniform float numColors;\n\nvarying", "uniform float numColors;\nvarying", 1)
    elif name == "comic.fs":
        text = text.replace('min="1" max="30"', 'min="2" max="30"', 1)
        start = text.index("precision highp float;")
        end = text.index("varying vec2 mainTexCoord;")
        declaration = re.sub(r"\n\n", "\n", text[start:end])
        text = text[:start] + declaration + text[end:]
    elif name == "threshold.fs":
        start = text.index("precision highp float;")
        end = text.index("varying vec2 mainTexCoord;")
        declaration = re.sub(r"\n\n", "\n", text[start:end])
        text = text[:start] + declaration + text[end:]
    elif name == "replacecolor.fs":
        start = text.index("uniform vec3 sampleColor;")
        end = text.index("varying vec2 mainTexCoord;")
        declaration = text[start:end]
        declaration = declaration.replace("uniform vec3 sampleColor;\n\nuniform vec4 replaceColor;\n\nuniform float threshold;\n\nuniform float intensity;", "uniform vec3 sampleColor;\nuniform vec4 replaceColor;\nuniform float threshold;\nuniform float intensity;")
        text = text[:start] + declaration + text[end:]
    else:
        raise ValueError(f"unsupported shader transformation: {entry}")

    start = text.index("void main()")
    brace = text.index("{", start)
    depth = 0
    for end in range(brace, len(text)):
        if text[end] == "{":
            depth += 1
        elif text[end] == "}":
            depth -= 1
            if depth == 0:
                text = text[:start] + SHADER_MAIN[name] + text[end + 1:]
                break
    else:
        raise ValueError(f"{entry}: unterminated main function")
    return text.encode("utf-8")


def apply(entry: str, data: bytes) -> bytes:
    match = CATALOG_ENTRY.fullmatch(entry)
    if match:
        return _catalog(data, match.group(1))
    if entry == ABOUT_ENTRY:
        return _about(data)
    if entry.startswith(SHADER_DIR):
        return _shader(entry, data)
    raise ValueError(f"unsupported resource transformation: {entry}")
