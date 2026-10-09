"""Check what depends on the JEI addons we intend to disable.

Disabling a jar (renaming it so the loader ignores it) removes the mod ids it
provided. Any other jar that hard-depends on those ids would fail to load, so
those have to be disabled or replaced too.
"""
import os
import sys
import zipfile
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify import parse_toml  # noqa: E402

MODS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                   "targets", "neoforge-1.21.1", "run", "mods"))

# The jars to disable, keyed by the mod id(s) they provide.
TARGETS = {
    "jeirecipemanager": "jeirecipemanager",
    "jei-unhidden": "jei_unhidden",
    "jei-trim-hider": "jei_trim_hider",
    "comprehension-jei-addon": "gatedjei",
    "progressivestages": "progressivestages",
    "recipe-item-sync": "recipeitemsync",
    "kubejs": "kubejs",
    "kubejs-jei-info-removal": "jeiinforemover",
}
DISABLED_IDS = set(TARGETS.values())

# Map every jar to the ids it provides and requires.
provided = defaultdict(list)
requires = defaultdict(list)
for f in sorted(os.listdir(MODS)):
    if not f.endswith(".jar"):
        continue
    with zipfile.ZipFile(os.path.join(MODS, f)) as z:
        for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
            ids, deps = parse_toml(z.read(name).decode("utf-8-sig", "replace"))
            for i in ids:
                provided[i].append(f)
            for d in deps:
                if d.get("type") == "required":
                    requires[f].append(d["modId"])

print("=== 待禁用 jar 提供的 mod id ===")
for slug, mid in TARGETS.items():
    jars = [f for f in os.listdir(MODS) if f.startswith(slug + "__")]
    print("  %-26s provides %-18s %s" % (slug, mid, jars[0] if jars else "（缺失）"))

print("\n=== 禁用后会失去依赖的 jar（必须一并处理）===")
found = False
for f, reqs in sorted(requires.items()):
    if f.startswith(tuple(s + "__" for s in TARGETS)):
        continue
    hit = [r for r in reqs if r in DISABLED_IDS]
    if hit:
        found = True
        print("  %-58s 需要 %s" % (f.split("__")[0], ", ".join(hit)))
if not found:
    print("  （无。没有任何保留的 jar 依赖这些 mod id）")

print("\n=== 待禁用的 jar 自身的依赖（确认它们不提供别处需要的东西）===")
for slug in TARGETS:
    for f in os.listdir(MODS):
        if f.startswith(slug + "__"):
            print("  %-26s requires %s" % (slug, ", ".join(requires.get(f, [])) or "（无）"))
