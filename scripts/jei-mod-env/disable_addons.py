"""Disable the JEI addons that fight the editor over recipe/ingredient visibility.

Renames the jar to `<name>.jar.disabled` instead of deleting it, so the change is
reversible and the file is still there for inspection. NeoForge only loads files
ending in `.jar`, so a disabled jar is simply ignored.

Also reports jars that became orphaned (their only dependents were disabled).
"""
import os
import sys
import zipfile
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify import parse_toml  # noqa: E402

MODS = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "..", "targets", "neoforge-1.21.1", "run", "mods"))

TARGETS = [
    "jeirecipemanager", "jei-unhidden", "jei-trim-hider", "comprehension-jei-addon",
    "progressivestages", "recipe-item-sync", "kubejs", "kubejs-jei-info-removal",
]


def modids(path):
    ids = []
    try:
        with zipfile.ZipFile(path) as z:
            for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
                got, _ = parse_toml(z.read(name).decode("utf-8-sig", "replace"))
                ids.extend(got)
    except Exception:
        pass
    return ids


disabled = []
for slug in TARGETS:
    matches = [f for f in os.listdir(MODS)
               if f.startswith(slug + "__") and f.endswith(".jar")]
    if not matches:
        print("  !! 未找到: %s" % slug)
        continue
    src = os.path.join(MODS, matches[0])
    dst = src + ".disabled"
    if os.path.exists(dst):
        os.remove(dst)
    os.rename(src, dst)
    disabled.append((slug, matches[0], modids(src)))
    print("  已禁用 %-26s -> %s" % (slug, os.path.basename(dst)))

# Which mod ids are now gone, and who is left with nothing to talk to?
gone = set()
for _, _, ids in disabled:
    gone.update(ids)

remaining, req_map = {}, {}
for f in sorted(os.listdir(MODS)):
    if not f.endswith(".jar"):
        continue
    ids = modids(os.path.join(MODS, f))
    remaining[f] = set(ids)
    with zipfile.ZipFile(os.path.join(MODS, f)) as z:
        for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
            _, deps = parse_toml(z.read(name).decode("utf-8-sig", "replace"))
            req_map[f] = {d["modId"] for d in deps if d.get("type") == "required"}

print("\n=== 现在成为孤儿的 jar（其全部使用者都已被禁用）===")
orphans = []
for f, reqs in sorted(req_map.items()):
    missing = reqs & gone
    if missing:
        print("  %-46s 失去前置: %s" % (f.split("__")[0], ", ".join(sorted(missing))))
    # rhino is only used by kubejs; report ids nobody requires any more.
for mid in ("rhino",):
    users = [f for f, r in req_map.items() if mid in r]
    if not users and any(mid in ids for ids in remaining.values()):
        orphans.append(mid)
        print("  %-46s 不再被任何保留的 jar 依赖（孤儿前置）" % mid)

print("\n共禁用 %d 个 jar" % len(disabled))
