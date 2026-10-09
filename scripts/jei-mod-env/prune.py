"""Prune the mod set to what NeoForge 21.1.238 + JEI 19.44.0.403 can actually run.

Roots are the 38 standalone addons verified earlier plus the 25 addons that need
content mods. Starting from those, any jar with an unsatisfiable required
dependency (missing mod, or a neoforge/jei version outside its declared range) is
removed, and the removal repeats because dropping one jar can break another.
Finally only jars reachable from the surviving roots are kept, so prerequisites
of dropped mods do not linger.
"""
import io
import json
import os
import re
import shutil
import sys
import zipfile
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(ROOT, "build", "tmp", "modsearch")
JARS = os.path.join(BASE, "jars")
MODS = os.path.join(ROOT, "targets", "neoforge-1.21.1", "run", "mods")

# The versions this target builds and runs against.
ENV = {"neoforge": "21.1.238", "minecraft": "1.21.1", "jei": "19.44.0.403"}
BUILTIN = {"minecraft", "neoforge", "forge", "fabricloader", "fabric", "java",
           "fml", "javafml", "lowcodefml"}


def version_tuple(text):
    return tuple(int(p) for p in re.findall(r"\d+", text)) if text else ()


def satisfies(version, expr):
    """Evaluate a Maven-style range: [lo,hi), (lo,hi], [lo,), bare version, *."""
    if not expr or expr.strip() == "*":
        return True
    expr = expr.strip().replace(" ", "")
    v = version_tuple(version)
    if not expr.startswith(("[", "(")):
        # A bare version means "at least this version".
        return v >= version_tuple(expr)

    lower_inclusive = expr[0] == "["
    upper_inclusive = expr[-1] == "]"
    inner = expr[1:-1]
    lo, _, hi = inner.partition(",")

    if lo:
        lo_v = version_tuple(lo)
        if v < lo_v or (v == lo_v and not lower_inclusive):
            return False
    if hi:
        hi_v = version_tuple(hi)
        if v > hi_v or (v == hi_v and not upper_inclusive):
            return False
    return True


def parse(jar):
    ids, deps = [], []
    try:
        with zipfile.ZipFile(jar) as z:
            blobs = []
            for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
                blobs.append(z.read(name).decode("utf-8-sig", "replace"))
            for name in z.namelist():
                if name.endswith(".jar") and ("jarjar" in name or "/jars/" in name):
                    try:
                        sub = zipfile.ZipFile(io.BytesIO(z.read(name)))
                        for x in [n for n in sub.namelist() if n.endswith("mods.toml")]:
                            blobs.append(sub.read(x).decode("utf-8-sig", "replace"))
                    except Exception:
                        pass
    except Exception as exc:
        return [], [], str(exc)

    for text in blobs:
        section = ""
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
    return ids, deps, None


def main():
    with open(os.path.join(BASE, "report.json"), encoding="utf-8") as f:
        base_keep = json.load(f)["keep"]
    roots = {os.path.basename(r["staged"]) for r in base_keep if "PastelJEI" not in r["staged"]}

    # Seeds are the addons that need content mods, pinned to the jars resolved earlier.
    for name in os.listdir(JARS):
        if not name.endswith(".jar"):
            continue
        ids, _, _ = parse(os.path.join(JARS, name))
        roots.add(name)

    # Restrict the starting point to the addon seeds, not every staged jar.
    SEEDS = [n for n in os.listdir(JARS) if n.endswith(".jar")]
    info = {}
    for name in SEEDS:
        ids, deps, err = parse(os.path.join(JARS, name))
        info[name] = {"ids": ids, "deps": deps, "err": err}

    # Only consider jars the earlier tree walk selected (recorded in tree.json).
    with open(os.path.join(BASE, "tree.json"), encoding="utf-8") as f:
        tree = json.load(f)
    selected = {os.path.basename(r["staged"]) for r in base_keep if "PastelJEI" not in r["staged"]}
    for key, rec in tree["records"].items():
        if key.startswith("modid:"):
            continue
        jar = rec.get("jar")
        if not jar:
            continue
        n = os.path.basename(jar)
        if n in info and "PastelJEI" not in n and not n.startswith("data-essence__"):
            selected.add(n)
    for n in os.listdir(JARS):
        if n.startswith("data-essence-03__"):
            selected.add(n)

    alive = set(selected)

    def provided(pool):
        out = defaultdict(list)
        for n in pool:
            for mid in info[n]["ids"]:
                if mid not in BUILTIN:
                    out[mid].append(n)
        return out

    dropped = []
    while True:
        have = provided(alive)
        bad = []
        for n in alive:
            for d in info[n]["deps"]:
                mid = d["modId"]
                typ = d.get("type")
                if typ not in ("required", "optional"):
                    continue
                # NeoForge enforces a versionRange on optional dependencies too,
                # as long as the depended-on mod is actually present. That is how
                # ae2tb fails on JEI 19.44: it declares jei as optional >=19.55.
                if mid in ENV:
                    if not satisfies(ENV[mid], d.get("range")):
                        bad.append((n, "requires %s %s, running %s"
                                    % (mid, d.get("range"), ENV[mid])))
                        break
                elif mid in BUILTIN:
                    continue
                elif typ == "required" and mid not in have:
                    bad.append((n, "missing required mod %s" % mid))
                    break
                elif typ == "optional" and mid in have:
                    # Present, so its versionRange must hold; unknown versions are
                    # accepted rather than guessed at.
                    continue
        if not bad:
            break
        for n, why in bad:
            dropped.append((n, why))
            alive.discard(n)

    # Keep only what the surviving roots actually reach.
    roots_alive = roots & alive
    reach, queue = set(roots_alive), list(roots_alive)
    while queue:
        n = queue.pop()
        for d in info[n]["deps"]:
            if d.get("type") != "required":
                continue
            for p in provided(alive).get(d["modId"], []):
                if p not in reach:
                    reach.add(p)
                    queue.append(p)
    # EMI and TooManyRecipeViewers are alternative recipe viewers, not JEI
    # dependencies. Several JEI addons even mark EMI as `discouraged` (which
    # raises a warning screen), so they are excluded unless something requires
    # them explicitly - and after pruning, nothing does.
    for n in list(reach):
        if n.split("__")[0] in ("emi", "tmrv"):
            reach.discard(n)
    orphans = alive - reach

    os.makedirs(MODS, exist_ok=True)
    for name in os.listdir(MODS):
        if name.endswith(".jar"):
            os.remove(os.path.join(MODS, name))
    for n in sorted(reach):
        shutil.copy2(os.path.join(JARS, n), os.path.join(MODS, n))

    json.dump({"dropped": [{"jar": n, "reason": w} for n, w in dropped],
               "orphans": sorted(orphans),
               "kept": sorted(reach)},
              open(os.path.join(BASE, "prune.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    print("installed %d jars" % len(reach))
    print("\nDROPPED (%d):" % len(dropped))
    for n, why in sorted(dropped):
        print("  %-58s %s" % (n.split("__")[0], why))
    if orphans:
        print("\nprerequisites of dropped mods, not installed (%d): %s"
              % (len(orphans), ", ".join(sorted(o.split("__")[0] for o in orphans))))


if __name__ == "__main__":
    main()
