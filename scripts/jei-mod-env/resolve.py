"""Resolve the full dependency tree for the JEI addons that need content mods.

Starts from the addon slugs, walks each jar's declared required mod ids, maps
every mod id back to a Modrinth project, and downloads the newest 1.21.1
NeoForge file for each. Modrinth dependency metadata is unreliable, so the
authoritative dependency list for a jar is read out of its own mods.toml.

State is persisted to build/tmp/modsearch/tree.json so the walk can resume.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(ROOT, "build", "tmp", "modsearch")
CACHE = os.path.join(BASE, "cache")
JARS = os.path.join(BASE, "jars")
UA = {"User-Agent": "jei-editor-research/1.0"}
MC = "1.21.1"
LOADER = "neoforge"
BUILTIN = {"minecraft", "neoforge", "forge", "fabricloader", "fabric", "java", "fml",
           "javafml", "lowcodefml"}
# JEI-side helper libraries, not content mods.
JEI_SIDE_LIBS = {"jeidrawables", "jei"}

# The addons that cannot run without another content mod.
SEEDS = [
    "jea", "just-enough-beacons-reforged", "jei-multiblocks",
    "just-enough-mekanism-multiblocks", "refined-storage-jei-integration",
    "sophisticated-jei-index", "create-jei-compat", "create-redstone-link-gui",
    "create-satisfied", "create-just-filter-stamps", "spectrumjei", "pasteljei",
    "halcyonjei", "silentgearjei", "ae2utility", "ae2-qol-client",
    "ae2-tangible-bookmarks", "irequesting", "advanced-loot-info",
    "advanced-worldgen-info", "cobbledex-rei-emi-jei", "jeac",
    "kubejs-jei-info-removal", "qio-sync", "emi-jei-grid-fix",
]


def get(url, key):
    path = os.path.join(CACHE, key + ".json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(url, headers=UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                data = json.load(r)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return data
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"__notfound__": True}
            if attempt == 3:
                return {"__error__": str(e)}
            time.sleep(2 + 3 * attempt)
        except Exception as e:
            if attempt == 3:
                return {"__error__": str(e)}
            time.sleep(2 + 3 * attempt)


def versions_for(pid_or_slug):
    return get("https://api.modrinth.com/v2/project/%s/version?" % pid_or_slug
               + urllib.parse.urlencode({"loaders": json.dumps([LOADER]),
                                         "game_versions": json.dumps([MC])}),
               "v_%s_%s_%s" % (pid_or_slug, LOADER, MC))


def pick(versions, want_release=True):
    """Newest release (falling back to beta/alpha) for the target MC/loader."""
    if not isinstance(versions, list) or not versions:
        return None
    pool = [v for v in versions if v.get("version_type") == "release"] if want_release else versions
    pool = pool or versions
    pool.sort(key=lambda v: v.get("date_published", ""), reverse=True)
    return pool[0]


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    req = urllib.request.Request(url, headers=UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=180) as r, open(dest + ".part", "wb") as out:
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(dest + ".part", dest)
            return True
        except Exception:
            if attempt == 3:
                return False
            time.sleep(3 + 3 * attempt)


def read_meta(jar):
    """mod ids plus required/optional/incompatible deps declared by a jar."""
    ids, deps = [], []
    try:
        with zipfile.ZipFile(jar) as z:
            for name in [n for n in z.namelist() if n.endswith("mods.toml")]:
                text = z.read(name).decode("utf-8-sig", "replace")
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
                    key, value = m.group(1), m.group(2)
                    if section.endswith("mods") and key == "modId" and value not in ids:
                        ids.append(value)
                    elif section.startswith("dependencies") and key == "modId":
                        deps.append([value.lower(), None])
                    elif section.startswith("dependencies") and key == "type" and deps:
                        # Metadata in the wild uses both `required` and `REQUIRED`.
                        deps[-1][1] = value.lower()
    except Exception:
        pass

    def collect(kind):
        out = []
        for mid, typ in deps:
            if mid in BUILTIN or mid == "jei":
                continue
            if typ == kind:
                out.append(mid)
        return sorted(set(out))

    return {"modids": ids, "required": collect("required"),
            "optional": collect("optional"),
            "incompatible": collect("incompatible")}


def file_of(version):
    return next((f for f in version["files"] if f.get("primary")), version["files"][0])


def find_provider(modid):
    """Map a mod id to the Modrinth project that provides it."""
    for slug in (modid, modid.replace("_", "-"), modid.replace("-", "")):
        proj = get("https://api.modrinth.com/v2/project/%s" % slug, "p_%s" % slug)
        if "__notfound__" in proj or "__error__" in proj:
            continue
        vs = versions_for(proj["id"])
        v = pick(vs) if isinstance(vs, list) else None
        if not v:
            continue
        f = file_of(v)
        dest = os.path.join(JARS, "%s__%s" % (proj["slug"], f["filename"]))
        if download(f["url"], dest) and modid in read_meta(dest)["modids"]:
            return {"slug": proj["slug"], "project_id": proj["id"], "jar": dest,
                    "version": v, "file": f, "via": "slug"}
    # Fall back to search.
    url = "https://api.modrinth.com/v2/search?" + urllib.parse.urlencode({
        "query": modid, "limit": 20,
        "facets": json.dumps([["versions:%s" % MC], ["categories:%s" % LOADER]])})
    res = get(url, "s_%s_%s" % (modid, MC))
    for hit in (res.get("hits", []) if isinstance(res, dict) else []):
        pid = hit["project_id"]
        vs = versions_for(pid)
        v = pick(vs) if isinstance(vs, list) else None
        if not v:
            continue
        f = file_of(v)
        dest = os.path.join(JARS, "%s__%s" % (hit["slug"], f["filename"]))
        if download(f["url"], dest) and modid in read_meta(dest)["modids"]:
            return {"slug": hit["slug"], "project_id": pid, "jar": dest,
                    "version": v, "file": f, "via": "search"}
    return None


def main():
    state_path = os.path.join(BASE, "tree.json")
    state = {"records": {}, "unresolved": {}, "pending": []}
    if os.path.exists(state_path):
        with open(state_path, encoding="utf-8") as f:
            state = json.load(f)
    records = state["records"]

    # Seed: addons already staged by the earlier pass.
    queue = []
    for slug in SEEDS:
        cands = [n for n in os.listdir(JARS) if n.startswith(slug + "__") and n.endswith(".jar")]
        if not cands:
            print("!! seed jar missing: %s" % slug, file=sys.stderr)
            continue
        queue.append(os.path.join(JARS, cands[0]))

    seen_modids = set()
    round_no = 0
    while queue:
        round_no += 1
        print("--- round %d: %d jar(s) to inspect" % (round_no, len(queue)), file=sys.stderr)
        needed = {}
        for jar in queue:
            meta = read_meta(jar)
            rel = os.path.relpath(jar, ROOT).replace("\\", "/")
            records[rel] = {"jar": rel, "modids": meta["modids"],
                            "required": meta["required"], "optional": meta["optional"],
                            "incompatible": meta["incompatible"]}
            print("  %-40s needs=%s%s" % (",".join(meta["modids"]), ",".join(meta["required"]),
                                          ("  incompat=%s" % ",".join(meta["incompatible"])) if meta["incompatible"] else ""))
            for mid in meta["required"]:
                if mid in JEI_SIDE_LIBS:
                    continue
                if mid not in seen_modids:
                    needed[mid] = jar

        # Which needed mod ids are already provided by something we have?
        provided = set()
        for rec in records.values():
            provided.update(rec.get("modids", []))
            if rec.get("modid"):
                provided.add(rec["modid"])
        missing = [m for m in needed if m not in provided]
        for m in needed:
            seen_modids.add(m)
        if not missing:
            queue = []
            break

        print("  resolving %d mod id(s): %s" % (len(missing), ", ".join(missing)), file=sys.stderr)

        def resolve(mid):
            rec = records.get("modid:" + mid)
            if rec and rec.get("jar"):
                p = os.path.join(ROOT, rec["jar"]) if not os.path.isabs(rec["jar"]) else rec["jar"]
                if os.path.exists(p):
                    return mid, p, None
            found = find_provider(mid)
            if not found:
                return mid, None, None
            return mid, found["jar"], found

        new_queue = []
        for mid, jar, found in ThreadPoolExecutor(6).map(resolve, missing):
            if jar is None:
                state["unresolved"][mid] = "no Modrinth 1.21.1 NeoForge provider"
                print("  !! unresolved: %s" % mid, file=sys.stderr)
                continue
            records["modid:" + mid] = {"modid": mid, "jar": os.path.relpath(jar, ROOT).replace("\\", "/"),
                                       "slug": (found or {}).get("slug"),
                                       "version": (found or {}).get("version", {}).get("version_number"),
                                       "url": (found or {}).get("file", {}).get("url")}
            if found:
                v = found["version"]
                records[os.path.relpath(jar, ROOT).replace("\\", "/")] = {
                    "jar": os.path.relpath(jar, ROOT).replace("\\", "/"),
                    "modids": [mid], "required": [], "optional": [],
                    "slug": found["slug"], "version": v["version_number"],
                    "url": found["file"]["url"], "page": "https://modrinth.com/mod/" + found["slug"],
                    "size": found["file"]["size"],
                }
            new_queue.append(jar)
        queue = new_queue

    state["records"] = records
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)
    print("\n== resolved %d records, %d unresolved ==" % (len(records), len(state["unresolved"])))
    for m, why in state["unresolved"].items():
        print("   %-24s %s" % (m, why))


if __name__ == "__main__":
    main()
