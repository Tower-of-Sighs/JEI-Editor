"""Verify a mod jar folder: collect provided mod ids (including jar-in-jar) and
check every declared required/incompatible dependency against the set.

NeoForge metadata is parsed section by section so that a dependency's `modId`
is not mistaken for a mod's own id, and nested jars (META-INF/jarjar,
META-INF/jars) are scanned because mods like Create ship Flywheel and Ponder
inside their own jar.
"""
import io
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

BUILTIN = {"minecraft", "neoforge", "forge", "fabricloader", "fabric", "java",
           "fml", "javafml", "lowcodefml"}


def parse_toml(text):
    """Return (mod ids declared by this file, dependency records)."""
    ids, deps, section = [], [], ""
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("["):
            section = line.strip("[]").strip()
            continue
        m = re.match(r'([A-Za-z_][\w.-]*)\s*=\s*"?([^"]*)"?', line)
        if not m:
            continue
        k, v = m.group(1), m.group(2)
        if section.endswith("mods") and k == "modId":
            if v not in ids:
                ids.append(v)
        elif section.startswith("dependencies"):
            if k == "modId":
                deps.append({"modId": v.lower(), "type": None, "range": None})
            elif k == "type" and deps:
                deps[-1]["type"] = v.lower()
            elif k == "versionRange" and deps:
                deps[-1]["range"] = v
    return ids, deps


def scan_jar(path, into_ids, into_deps, label=None, depth=0):
    """Collect mod ids and deps from a jar and, recursively, its nested jars."""
    try:
        with zipfile.ZipFile(path) as z:
            for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
                ids, deps = parse_toml(z.read(name).decode("utf-8-sig", "replace"))
                into_ids.update(ids)
                for d in deps:
                    d["from"] = label or os.path.basename(path)
                    d["nested"] = depth > 0
                    into_deps.append(d)
            if depth < 3:
                for n in z.namelist():
                    if n.endswith(".jar") and ("jarjar" in n or "/jars/" in n):
                        try:
                            sub = zipfile.ZipFile(io.BytesIO(z.read(n)))
                            for name in [x for x in sub.namelist() if x.endswith("mods.toml")]:
                                ids, deps = parse_toml(sub.read(name).decode("utf-8-sig", "replace"))
                                into_ids.update(ids)
                                for d in deps:
                                    d["from"] = label or os.path.basename(path)
                                    d["nested"] = True
                                    into_deps.append(d)
                        except Exception:
                            pass
    except Exception as exc:
        print("  !! cannot read %s: %s" % (path, exc))


def verify(folder):
    ids, deps = set(), []
    files = sorted(f for f in os.listdir(folder) if f.endswith(".jar"))
    for f in files:
        scan_jar(os.path.join(folder, f), ids, deps, label=f)
    provided = ids | BUILTIN

    missing, incompatible = defaultdict(list), defaultdict(list)
    for d in deps:
        mid = d["modId"]
        if mid in BUILTIN:
            continue
        if d.get("type") == "required" and mid not in provided:
            missing[mid].append(d)
        if d.get("type") == "incompatible" and mid in provided:
            incompatible[mid].append(d)

    return files, provided, missing, incompatible


def main(folder):
    files, provided, missing, incompatible = verify(folder)
    print("jars: %d   distinct mod ids provided: %d" % (len(files), len(provided)))
    if missing:
        print("\nMISSING REQUIRED DEPENDENCIES")
        for mid, ds in sorted(missing.items()):
            srcs = sorted({d["from"] for d in ds if not d.get("nested")}) or sorted({d["from"] for d in ds})
            print("  %-26s required by: %s" % (mid, ", ".join(srcs)))
    else:
        print("\nno missing required dependencies")
    if incompatible:
        print("\nINCOMPATIBLE PAIRS PRESENT")
        for mid, ds in sorted(incompatible.items()):
            print("  %-26s declared incompatible by: %s" % (mid, ", ".join(sorted({d["from"] for d in ds}))))
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else
                  os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                               "targets", "neoforge-1.21.1", "run", "mods"))))
