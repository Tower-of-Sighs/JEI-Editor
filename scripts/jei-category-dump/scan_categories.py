"""Static cross-check: enumerate JEI RecipeType UIDs and category classes.

Runtime is the authoritative source (see jei-category-dump.txt), but this scan
works on the installed jars alone so the compatibility checklist can be
reviewed without booting the game.

For every class file it:
  * records classes that implement mezz/jei/api/recipe/category/IRecipeCategory
  * parses the constant pool + Code attributes and resolves every
    RecipeType.create(String namespace, String path, Class) call site
  * scans jar-in-jar entries and attributes them to the outer jar
"""
import json
import os
import re
import struct
import zipfile
from collections import defaultdict

MODS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                    "targets", "neoforge-1.21.1", "run", "mods"))
RECIPE_TYPE = "mezz/jei/api/recipe/RecipeType"
CATEGORY = "mezz/jei/api/recipe/category/IRecipeCategory"
ABSTRACT_CATEGORY = "mezz/jei/api/recipe/category/AbstractRecipeCategory"

UTF8, INTEGER, FLOAT, LONG, DOUBLE = 1, 3, 4, 5, 6
CLASS, STRING = 7, 8
FIELDREF, METHODREF, IFMETHODREF, NAMEANDTYPE = 9, 10, 11, 12
METHODHANDLE, METHODTYPE, DYNAMIC, INVOKEDYNAMIC, MODULE, PACKAGE = 15, 16, 17, 18, 19, 20


def parse_pool(data):
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    tags = [0] * count
    pos = 10
    index = 1
    while index < count:
        tag = data[pos]
        tags[index] = tag
        pos += 1
        if tag == UTF8:
            length = struct.unpack_from(">H", data, pos)[0]
            pool[index] = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag in (INTEGER, FLOAT):
            pos += 4
        elif tag in (LONG, DOUBLE):
            pos += 8
            index += 1
        elif tag in (CLASS, STRING, METHODTYPE, MODULE, PACKAGE):
            pool[index] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag in (FIELDREF, METHODREF, IFMETHODREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC):
            pool[index] = struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == METHODHANDLE:
            pos += 3
        else:
            raise ValueError("unknown constant tag %d at %d" % (tag, pos - 1))
        index += 1
    return pool, tags, pos


def utf8(pool, index):
    value = pool[index] if 0 < index < len(pool) else None
    return value if isinstance(value, str) else None


def class_name(pool, index):
    value = pool[index] if 0 < index < len(pool) else None
    if isinstance(value, int):
        return utf8(pool, value)
    return None


def method_ref(pool, index):
    entry = pool[index] if 0 < index < len(pool) else None
    if not isinstance(entry, tuple):
        return None, None
    owner = class_name(pool, entry[0])
    name_type = pool[entry[1]] if 0 < entry[1] < len(pool) else None
    if not isinstance(name_type, tuple):
        return owner, None
    return owner, utf8(pool, name_type[0])


def skip_attributes(data, pos, pool, tags, collect):
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(count):
        name = utf8(pool, struct.unpack_from(">H", data, pos)[0])
        length = struct.unpack_from(">I", data, pos + 2)[0]
        body = pos + 6
        if name == "Code" and collect is not None:
            collect(data, body, pool, tags)
        pos = body + length
    return pos


def scan_code(data, pos, pool, tags, found):
    max_stack, max_locals = struct.unpack_from(">HH", data, pos)
    code_len = struct.unpack_from(">I", data, pos + 4)[0]
    code = data[pos + 8:pos + 8 + code_len]
    i = 0
    recent = []
    while i < len(code):
        op = code[i]
        if op == 0x12:  # ldc
            idx = code[i + 1]
            i += 2
        elif op == 0x13:  # ldc_w
            idx = struct.unpack_from(">H", code, i + 1)[0]
            i += 3
        elif op == 0x14:  # ldc2_w
            i += 3
            continue
        elif op == 0xB8:  # invokestatic
            idx = struct.unpack_from(">H", code, i + 1)[0]
            owner, name = method_ref(pool, idx)
            if owner == RECIPE_TYPE and name == "create" and len(recent) >= 2:
                found.append((recent[-2], recent[-1]))
            i += 3
            continue
        elif op in (0xB6, 0xB7, 0xB9, 0xBA, 0xBB, 0xBD, 0xC0, 0xC1):
            i += 3
            continue
        elif op == 0x84 or op in (0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9):
            i += 2
            continue
        elif op == 0xAA or op == 0xAB:  # tableswitch / lookupswitch
            pad = (4 - (i + 1) % 4) % 4
            base = i + 1 + pad
            if op == 0xAA:
                low, high = struct.unpack_from(">ii", code, base + 4)
                i = base + 12 + 4 * (high - low + 1)
            else:
                npairs = struct.unpack_from(">i", code, base + 4)[0]
                i = base + 8 + 8 * npairs
            continue
        elif op == 0xC5:  # multianewarray
            i += 4
            continue
        else:
            i += 1
            continue
        # Only plain String constants feed the argument window; Class constants
        # are loaded with the same ldc opcode but are not create() arguments.
        if 0 < idx < len(pool) and tags[idx] == STRING:
            value = utf8(pool, pool[idx])
            if value is not None:
                recent.append(value)
                if len(recent) > 4:
                    recent.pop(0)


def analyse_class(data):
    pool, tags, pos = parse_pool(data)
    access, this_index, super_index = struct.unpack_from(">HHH", data, pos)
    pos += 6
    interfaces = struct.unpack_from(">H", data, pos)[0]
    iface_names = [class_name(pool, struct.unpack_from(">H", data, pos + 2 + 2 * i)[0])
                   for i in range(interfaces)]
    pos += 2 + 2 * interfaces
    names = [class_name(pool, super_index)] + iface_names
    types = []
    field_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(field_count):
        pos += 6
        pos = skip_attributes(data, pos, pool, tags, None)
    method_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(method_count):
        pos += 6
        pos = skip_attributes(data, pos, pool, tags,
                              lambda d, b, p, t: scan_code(d, b, p, t, types))
    return names, types


def scan_jar(path, jar_label, uids, categories, modids):
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            for name in names:
                if name == "META-INF/neoforge.mods.toml":
                    text = z.read(name).decode("utf-8", "replace")
                    for match in re.finditer(r'modId\s*=\s*"([^"]+)"', text):
                        modids[jar_label].add(match.group(1))
            for name in names:
                if not name.endswith(".class"):
                    continue
                data = z.read(name)
                if RECIPE_TYPE.encode() not in data and CATEGORY.encode() not in data:
                    continue
                try:
                    interfaces, types = analyse_class(data)
                except Exception:
                    continue
                for owner in interfaces:
                    if owner in (CATEGORY, ABSTRACT_CATEGORY):
                        categories[jar_label].add(name[:-6].replace("/", "."))
                        break
                for namespace, uid_path in types:
                    if namespace and uid_path:
                        uids["%s:%s" % (namespace, uid_path)].add(jar_label)
            for name in names:
                if name.endswith(".jar") and name.startswith("META-INF/jarjar/"):
                    import io
                    with zipfile.ZipFile(io.BytesIO(z.read(name))) as nested:
                        for entry in nested.namelist():
                            if entry.endswith(".class"):
                                data = nested.read(entry)
                                if RECIPE_TYPE.encode() not in data and CATEGORY.encode() not in data:
                                    continue
                                try:
                                    interfaces, types = analyse_class(data)
                                except Exception:
                                    continue
                                for owner in interfaces:
                                    if owner in (CATEGORY, ABSTRACT_CATEGORY):
                                        categories[jar_label].add(entry[:-6].replace("/", "."))
                                        break
                                for namespace, uid_path in types:
                                    if namespace and uid_path:
                                        uids["%s:%s" % (namespace, uid_path)].add(jar_label)
    except Exception as exc:
        print("  !! %s: %s" % (jar_label, exc))


uids = defaultdict(set)
categories = defaultdict(set)
modids = defaultdict(set)

for entry in sorted(os.listdir(MODS)):
    if not entry.endswith(".jar"):
        continue
    label = entry.split("__")[0]
    scan_jar(os.path.join(MODS, entry), label, uids, categories, modids)

print("=== RecipeType UIDs found by static scan: %d ===" % len(uids))
for uid in sorted(uids):
    jars = ",".join(sorted(uids[uid]))
    mods = ",".join(sorted(set().union(*[modids[j] for j in sorted(uids[uid])])) or {""})
    print("  %-52s %-24s %s" % (uid, mods, jars))

print()
print("=== Category classes: %d ===" % sum(len(v) for v in categories.values()))
for jar in sorted(categories):
    for cls in sorted(categories[jar]):
        print("  %-24s %s" % (jar, cls))

with open(os.path.join(os.path.dirname(__file__), "static_categories.json"), "w", encoding="utf-8") as handle:
    json.dump({
        "uids": {k: sorted(v) for k, v in uids.items()},
        "categories": {k: sorted(v) for k, v in categories.items()},
    }, handle, ensure_ascii=False, indent=1)
