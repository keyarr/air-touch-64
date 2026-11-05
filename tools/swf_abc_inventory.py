#!/usr/bin/env python3
"""Print a symbol and method-body inventory for the sole DoABC in a SWF."""

import argparse
import hashlib
import json
import pathlib
import struct
import zlib


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, size):
        start = self.pos
        self.pos += size
        if self.pos > len(self.data):
            raise ValueError("truncated ABC")
        return self.data[start:self.pos]

    def u8(self):
        return self.take(1)[0]

    def u16(self):
        return struct.unpack("<H", self.take(2))[0]

    def u30(self):
        return self.varint(0x3FFFFFFF)

    def u32(self):
        return self.varint(0xFFFFFFFF)

    def varint(self, maximum):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.u8()
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                if value > maximum:
                    raise ValueError("invalid variable integer")
                return value
        raise ValueError("invalid variable integer")

    def string(self):
        return self.take(self.u30()).decode("utf-8", errors="replace")

    def skip(self, size):
        self.take(size)


def count(reader):
    return reader.u30()


def pool(reader, read_item):
    size = count(reader)
    return [None] + [read_item() for _ in range(max(0, size - 1))]


def parse_abc(data):
    r = Reader(data)
    minor, major = r.u16(), r.u16()
    ints = pool(r, r.u32)
    uints = pool(r, r.u32)
    doubles = pool(r, lambda: r.take(8).hex())
    strings = pool(r, r.string)

    namespaces = [None]
    for _ in range(max(0, count(r) - 1)):
        kind, name = r.u8(), r.u30()
        namespaces.append((kind, name))
    ns_sets = [None]
    for _ in range(max(0, count(r) - 1)):
        ns_sets.append([r.u30() for _ in range(count(r))])

    multinames = [None]
    for _ in range(max(0, count(r) - 1)):
        kind = r.u8()
        if kind in (0x07, 0x0D):
            value = (kind, r.u30(), r.u30())
        elif kind in (0x0F, 0x10):
            value = (kind, r.u30())
        elif kind in (0x11, 0x12):
            value = (kind,)
        elif kind in (0x09, 0x0E):
            value = (kind, r.u30(), r.u30())
        elif kind in (0x1B, 0x1C):
            value = (kind, r.u30())
        elif kind == 0x1D:
            base = r.u30()
            value = (kind, base, [r.u30() for _ in range(count(r))])
        else:
            raise ValueError(f"unsupported multiname kind 0x{kind:02x}")
        multinames.append(value)

    methods = []
    for _ in range(count(r)):
        param_count = count(r)
        ret = r.u30()
        params = [r.u30() for _ in range(param_count)]
        name, flags = r.u30(), r.u8()
        optional = []
        if flags & 0x08:
            optional = [(r.u30(), r.u8()) for _ in range(count(r))]
        names = [r.u30() for _ in range(param_count)] if flags & 0x80 else []
        methods.append({"params": params, "return": ret, "name": name, "flags": flags,
                        "optional": optional, "paramNames": names})

    metadata = []
    for _ in range(count(r)):
        name = r.u30()
        items = [(r.u30(), r.u30()) for _ in range(count(r))]
        metadata.append((name, items))

    def traits():
        result = []
        for _ in range(count(r)):
            name, tag = r.u30(), r.u8()
            kind, attrs = tag & 0x0F, tag >> 4
            item = {"name": name, "kind": kind, "attrs": attrs}
            if kind in (0, 6):
                item["slot"] = r.u30()
                item["type"] = r.u30()
                item["value"] = r.u30()
                if item["value"]:
                    item["valueKind"] = r.u8()
            elif kind in (1, 2, 3):
                item["dispatch"] = r.u30()
                item["method"] = r.u30()
            elif kind == 4:
                item["slot"] = r.u30()
                item["class"] = r.u30()
            elif kind == 5:
                item["slot"] = r.u30()
                item["function"] = r.u30()
            else:
                raise ValueError(f"unsupported trait kind {kind}")
            if attrs & 0x04:
                item["metadata"] = [r.u30() for _ in range(count(r))]
            result.append(item)
        return result

    instances = []
    for _ in range(count(r)):
        name, super_name, flags = r.u30(), r.u30(), r.u8()
        protected = r.u30() if flags & 0x08 else 0
        interfaces = [r.u30() for _ in range(count(r))]
        init = r.u30()
        instances.append({"name": name, "super": super_name, "flags": flags,
                          "protected": protected, "interfaces": interfaces,
                          "init": init, "traits": traits()})

    classes = [{"init": r.u30(), "traits": traits()} for _ in instances]
    scripts = [{"init": r.u30(), "traits": traits()} for _ in range(count(r))]
    bodies = []
    for _ in range(count(r)):
        method = r.u30()
        stack, locals_, init_scope, max_scope = (r.u30(), r.u30(), r.u30(), r.u30())
        code = r.take(count(r))
        exceptions = []
        for _ in range(count(r)):
            exceptions.append([r.u30() for _ in range(5)])
        body_traits = traits()
        bodies.append({"method": method, "maxStack": stack, "locals": locals_,
                       "initScope": init_scope, "maxScope": max_scope,
                       "codeSize": len(code), "codeSha256": hashlib.sha256(code).hexdigest(),
                       "exceptions": exceptions, "traits": body_traits})

    if r.pos != len(data):
        raise ValueError(f"{len(data) - r.pos} unparsed ABC bytes")

    def namespace(index):
        if index == 0:
            return "*"
        kind, name = namespaces[index]
        return f"{kind}:{strings[name] if name else ''}"

    def multiname(index):
        if index == 0:
            return "*"
        value = multinames[index]
        kind = value[0]
        if kind in (0x07, 0x0D):
            ns = namespace(value[1])
            local = strings[value[2]] if value[2] else "*"
            return f"{ns}::{local}"
        if kind in (0x0F, 0x10):
            return f"RTQName({strings[value[1]] if value[1] else '*'})"
        if kind in (0x11, 0x12):
            return "RTQName(*)"
        if kind in (0x09, 0x0E):
            members = ",".join(namespace(index) for index in ns_sets[value[2]])
            return f"Multiname({strings[value[1]] if value[1] else '*'};{members})"
        if kind in (0x1B, 0x1C):
            members = ",".join(namespace(index) for index in ns_sets[value[1]])
            return f"MultinameL({members})"
        if kind == 0x1D:
            params = ",".join(multiname(index) for index in value[2])
            return f"{multiname(value[1])}.<{params}>"
        return "*"

    method_names = [strings[m["name"]] if m["name"] else "" for m in methods]
    body_by_method = {body["method"]: body for body in bodies}

    def format_traits(items, owner):
        output = []
        for item in items:
            entry = {"owner": owner, "name": multiname(item["name"]), "kind": item["kind"]}
            entry["attrs"] = item["attrs"]
            if "method" in item:
                idx = item["method"]
                entry["method"] = idx
                entry["methodName"] = method_names[idx]
                entry["methodFlags"] = methods[idx]["flags"]
                entry["signature"] = {
                    "return": multiname(methods[idx]["return"]),
                    "params": [multiname(param) for param in methods[idx]["params"]],
                }
                entry["body"] = body_by_method.get(idx)
            if "type" in item:
                entry["type"] = multiname(item["type"])
            if "dispatch" in item:
                entry["dispatch"] = item["dispatch"]
            if "slot" in item:
                entry["slot"] = item["slot"]
            if "class" in item:
                entry["class"] = item["class"]
            if "function" in item:
                entry["function"] = item["function"]
            if "value" in item:
                entry["value"] = item["value"]
                entry["valueKind"] = item.get("valueKind")
                if item["value"]:
                    value_kind = item["valueKind"]
                    index = item["value"]
                    if value_kind == 0x03:
                        value = ints[index]
                        entry["defaultValue"] = value if value < 0x80000000 else value - 0x100000000
                    elif value_kind == 0x04:
                        entry["defaultValue"] = uints[index]
                    elif value_kind == 0x06:
                        value = struct.unpack("<d", bytes.fromhex(doubles[index]))[0]
                        entry["defaultValue"] = "NaN" if value != value else value
                    elif value_kind == 0x01:
                        entry["defaultValue"] = strings[index]
                    elif value_kind == 0x0A:
                        entry["defaultValue"] = False
                    elif value_kind == 0x0B:
                        entry["defaultValue"] = True
                    elif value_kind == 0x0C:
                        entry["defaultValue"] = None
                    elif value_kind == 0x00:
                        entry["defaultValue"] = "undefined"
                    else:
                        entry["defaultValue"] = f"constant-kind-{value_kind}:{index}"
                else:
                    entry["defaultValue"] = "implicit"
            if "metadata" in item:
                entry["metadata"] = item["metadata"]
            output.append(entry)
        return output

    symbols = []
    for index, instance in enumerate(instances):
        owner = multiname(instance["name"])
        symbols.append({"owner": owner, "name": "#instance_init", "kind": "init",
                        "method": instance["init"], "signature": {
                            "return": "void", "params": []},
                        "body": body_by_method.get(instance["init"])})
        symbols += format_traits(instance["traits"], owner)
        symbols.append({"owner": owner, "name": "#static_init", "kind": "init",
                        "method": classes[index]["init"], "signature": {
                            "return": "void", "params": []},
                        "body": body_by_method.get(classes[index]["init"])})
        symbols += format_traits(classes[index]["traits"], owner + "#static")
    for index, script in enumerate(scripts):
        symbols.append({"owner": f"script[{index}]", "name": "#init", "kind": "init",
                        "method": script["init"], "signature": {
                            "return": "void", "params": []},
                        "body": body_by_method.get(script["init"])})
        symbols += format_traits(script["traits"], f"script[{index}]")
    metadata_symbols = [
        {"name": strings[name], "items": [
            [strings[key] if key else "", strings[value] if value else ""]
            for key, value in items
        ]}
        for name, items in metadata
    ]
    namespaces_resolved = [namespace(index) for index in range(1, len(namespaces))]
    namespace_sets_resolved = [
        [namespace(index) for index in members] for members in ns_sets[1:]
    ]
    multinames_resolved = [multiname(index) for index in range(1, len(multinames))]
    trait_count = sum(len(item["traits"]) for item in instances)
    trait_count += sum(len(item["traits"]) for item in classes)
    trait_count += sum(len(item["traits"]) for item in scripts)
    body_trait_count = sum(len(item["traits"]) for item in bodies)
    exception_count = sum(len(body["exceptions"]) for body in bodies)

    def pool_hash(values):
        encoded = json.dumps(values, ensure_ascii=False, separators=(",", ":")).encode()
        return hashlib.sha256(encoded).hexdigest()

    return {"version": [minor, major], "poolCounts": {
        "ints": len(ints) - 1, "uints": len(uints) - 1, "doubles": len(doubles) - 1,
        "strings": len(strings) - 1, "namespaces": len(namespaces) - 1,
        "namespaceSets": len(ns_sets) - 1, "multinames": len(multinames) - 1,
    }, "poolHashes": {
        "strings": pool_hash(strings[1:]),
        "namespaces": pool_hash(namespaces_resolved),
        "namespaceSets": pool_hash(namespace_sets_resolved),
        "multinames": pool_hash(multinames_resolved),
    }, "namespaceKindCounts": {
        str(kind): sum(1 for item in namespaces[1:] if item[0] == kind)
        for kind in sorted({item[0] for item in namespaces[1:]})
    }, "methodCount": len(methods), "classCount": len(instances),
        "scriptCount": len(scripts), "bodyCount": len(bodies), "traitCount": trait_count,
        "bodyTraitCount": body_trait_count,
        "metadataCount": len(metadata_symbols), "exceptionCount": exception_count,
        "metadata": metadata_symbols, "symbols": symbols, "bodies": bodies}


def swf_abc(raw):
    if raw[:3] == b"CWS":
        body = zlib.decompress(raw[8:])
    elif raw[:3] == b"FWS":
        body = raw[8:]
    else:
        raise ValueError("unsupported SWF signature")
    pos = 0
    first = body[pos]
    pos += 1
    rect_size = (5 + 4 * (first >> 3) + 7) // 8
    pos += rect_size - 1
    pos += 4
    abcs = []
    while pos < len(body):
        start = pos
        header = struct.unpack_from("<H", body, pos)[0]
        pos += 2
        tag_type, size = header >> 6, header & 0x3F
        if size == 0x3F:
            size = struct.unpack_from("<I", body, pos)[0]
            pos += 4
        end = pos + size
        if end > len(body):
            raise ValueError("SWF tag exceeds body")
        if tag_type == 82:
            payload = body[pos:end]
            nul = payload.index(0, 4)
            abcs.append(payload[nul + 1:])
        pos = end
        if tag_type == 0:
            break
        if pos <= start:
            raise ValueError("invalid SWF tag length")
    if len(abcs) != 1:
        raise ValueError(f"expected one DoABC tag, found {len(abcs)}")
    return parse_abc(abcs[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("swf", type=pathlib.Path)
    parser.add_argument("--compare", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    result = swf_abc(args.swf.read_bytes())
    if args.compare:
        other = swf_abc(args.compare.read_bytes())

        def methods(inventory):
            return {
                (symbol["owner"], symbol["name"], symbol["kind"],
                 json.dumps(symbol["signature"], sort_keys=True)): symbol["body"]["codeSha256"]
                for symbol in inventory["symbols"] if symbol.get("body") is not None
            }

        before, after = methods(result), methods(other)
        changed = sorted(key for key in before.keys() & after.keys() if before[key] != after[key])
        added = sorted(after.keys() - before.keys())
        removed = sorted(before.keys() - after.keys())

        def trait_key(symbol):
            identity = {key: symbol[key] for key in ("owner", "name", "kind", "signature", "type")
                        if key in symbol}
            return json.dumps(identity, sort_keys=True)

        def traits(inventory):
            return {trait_key(symbol): symbol for symbol in inventory["symbols"]}

        before_traits, after_traits = traits(result), traits(other)
        added_traits = sorted(after_traits.keys() - before_traits.keys())
        removed_traits = sorted(before_traits.keys() - after_traits.keys())
        changed_traits = sorted(
            key for key in before_traits.keys() & after_traits.keys()
            if any(before_traits[key].get(field) != after_traits[key].get(field)
                   for field in ("attrs", "slot", "defaultValue", "metadata", "dispatch", "class", "function"))
        )

        def exception_tables(inventory):
            return {
                (symbol["owner"], symbol["name"], symbol["kind"],
                 json.dumps(symbol.get("signature", {}), sort_keys=True)):
                symbol["body"]["exceptions"]
                for symbol in inventory["symbols"]
                if symbol.get("body") is not None and symbol["body"]["exceptions"]
            }

        before_exceptions = exception_tables(result)
        after_exceptions = exception_tables(other)
        changed_exceptions = sorted(
            key for key in before_exceptions.keys() & after_exceptions.keys()
            if before_exceptions[key] != after_exceptions[key]
        )
        summary = {
            "before": {"poolCounts": result["poolCounts"], "methods": result["methodCount"],
                       "classes": result["classCount"], "scripts": result["scriptCount"],
                       "bodies": result["bodyCount"], "traits": result["traitCount"],
                       "bodyTraits": result["bodyTraitCount"],
                       "metadata": result["metadataCount"], "exceptions": result["exceptionCount"],
                       "poolHashes": result["poolHashes"],
                       "namespaceKindCounts": result["namespaceKindCounts"]},
            "after": {"poolCounts": other["poolCounts"], "methods": other["methodCount"],
                      "classes": other["classCount"], "scripts": other["scriptCount"],
                      "bodies": other["bodyCount"], "traits": other["traitCount"],
                      "bodyTraits": other["bodyTraitCount"],
                      "metadata": other["metadataCount"], "exceptions": other["exceptionCount"],
                      "poolHashes": other["poolHashes"],
                      "namespaceKindCounts": other["namespaceKindCounts"]},
            "changedMethodBodies": ["::".join(map(str, key)) for key in changed],
            "addedMethodBodies": ["::".join(map(str, key)) for key in added],
            "removedMethodBodies": ["::".join(map(str, key)) for key in removed],
            "changedExceptionTables": ["::".join(map(str, key)) for key in changed_exceptions],
            "addedTraits": added_traits,
            "removedTraits": removed_traits,
            "changedTraits": changed_traits,
            "addedMetadata": sorted(set(map(json.dumps, other["metadata"])) -
                                     set(map(json.dumps, result["metadata"]))),
            "removedMetadata": sorted(set(map(json.dumps, result["metadata"])) -
                                       set(map(json.dumps, other["metadata"]))),
        }
        text = json.dumps(summary, indent=2, sort_keys=True) + "\n"
    else:
        text = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
