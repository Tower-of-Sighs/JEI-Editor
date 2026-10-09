"""Stage every 1.21.1 NeoForge JEI-related candidate jar and read its real metadata.

Modrinth dependency metadata is unreliable (it omits e.g. Just Enough Resources'
JEI dependency), so classification is done from the jar itself:
  * declared mod ids, from META-INF/neoforge.mods.toml
  * declared dependencies on `jei`, from the same file
  * whether the jar references the JEI API package at all (class constant pools)
  * whether it ships a JEI plugin descriptor
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CACHE = os.path.join(ROOT, "build", "tmp", "modsearch", "cache")
STAGE = os.path.join(ROOT, "build", "tmp", "modsearch", "jars")
UA = {"User-Agent": "jei-editor-research/1.0"}
os.makedirs(CACHE, exist_ok=True)
os.makedirs(STAGE, exist_ok=True)


def get(url, key):
    path = os.path.join(CACHE, key + ".json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(url, headers=UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.load(r)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return data
        except Exception:
            if attempt == 3:
                return {"__error__": "fetch failed"}
            time.sleep(2 + 3 * attempt)


def download(url, dest):
    if os.path.exists(dest):
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


def parse_toml(text):
    """Minimal TOML reader for mod metadata: sections, modId, dependencies."""
    modids, deps, section = [], [], ""
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
        if section.endswith("mods") and key == "modId":
            modids.append(value)
        elif section.startswith("dependencies") and key == "modId":
            deps.append({"modId": value.lower(), "type": None})
        elif section.startswith("dependencies") and key == "type" and deps:
            # Metadata in the wild uses both `required` and `REQUIRED`.
            deps[-1]["type"] = value.lower()
    return modids, deps


def inspect(jar):
    info = {"modids": [], "deps": [], "jei_required": False, "jei_optional": False,
            "required_mod_ids": [], "optional_mod_ids": [],
            "jei_api_refs": 0, "plugin_descriptors": [], "error": None}
    try:
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()
            tomls = [n for n in names if n.endswith("mods.toml")]
            for name in tomls:
                modids, deps = parse_toml(zf.read(name).decode("utf-8-sig", "replace"))
                info["modids"] += [m for m in modids if m not in info["modids"]]
                info["deps"] += deps
            for d in info["deps"]:
                if d["modId"] == "jei":
                    if d["type"] == "required":
                        info["jei_required"] = True
                    else:
                        info["jei_optional"] = True
                elif d["modId"] not in ("minecraft", "neoforge", "forge", "fabricloader", "fabric"):
                    if d["type"] == "required":
                        info["required_mod_ids"].append(d["modId"])
                    elif d["type"] == "optional":
                        info["optional_mod_ids"].append(d["modId"])
            info["plugin_descriptors"] = [
                n for n in names
                if re.search(r"jei_plugin|jeiplugin|/jei/", n, re.I) and n.endswith((".json", ".toml"))
            ]
            # Constant-pool scan: does any class reference the JEI API package?
            refs = 0
            for n in names:
                if not n.endswith(".class"):
                    continue
                try:
                    data = zf.read(n)
                except Exception:
                    continue
                if b"mezz/jei" in data:
                    refs += 1
            info["jei_api_refs"] = refs
    except Exception as exc:
        info["error"] = str(exc)
    return info


def pick_version(versions):
    for wanted in ("release", "beta", "alpha"):
        pool = [v for v in versions if v.get("version_type") == wanted]
        if pool:
            pool.sort(key=lambda v: v.get("date_published", ""), reverse=True)
            return pool[0]
    return None


def resolve(slug):
    """Look up the newest 1.21.1 NeoForge primary file for a project."""
    proj = get("https://api.modrinth.com/v2/project/%s" % slug, "proj_slug_%s" % slug)
    if "__error__" in proj:
        print("ERR project %s" % slug, file=sys.stderr)
        return None
    pid = proj["id"]
    versions = get(
        "https://api.modrinth.com/v2/project/%s/version?" % pid
        + urllib.parse.urlencode({"loaders": json.dumps(["neoforge"]),
                                  "game_versions": json.dumps(["1.21.1"])}),
        "ver_%s" % slug,
    )
    if isinstance(versions, dict) or not versions:
        print("SKIP %s (no 1.21.1 neoforge version)" % slug, file=sys.stderr)
        return None
    version = pick_version(versions)
    primary = next((f for f in version["files"] if f.get("primary")), version["files"][0])
    dest = os.path.join(STAGE, "%s__%s" % (slug, primary["filename"]))
    return proj, pid, version, primary, dest


def main(slugs):
    records = []
    import concurrent.futures as cf

    resolved = []
    with cf.ThreadPoolExecutor(8) as ex:
        for res in ex.map(resolve, slugs):
            if res:
                resolved.append(res)

    # The CDN throttles a single connection hard (~65 KB/s); eight workers
    # roughly triple aggregate throughput.
    def fetch(res):
        proj, pid, version, primary, dest = res
        return res, download(primary["url"], dest)

    done = []
    with cf.ThreadPoolExecutor(8) as ex:
        for res, ok in ex.map(fetch, resolved):
            slug = res[0]["title"]
            if not ok:
                print("DLFAIL %s" % res[0]["slug"], file=sys.stderr)
                continue
            done.append(res)

    for i, (proj, pid, version, primary, dest) in enumerate(done, 1):
        slug = proj["slug"]
        digest = hashlib.sha1(open(dest, "rb").read()).hexdigest()
        info = inspect(dest)
        records.append({
            "slug": slug, "project_id": pid, "title": proj["title"],
            "summary": proj.get("description", ""),
            "downloads": proj.get("downloads"),
            "version_number": version["version_number"],
            "version_type": version["version_type"],
            "date_published": version.get("date_published", "")[:10],
            "filename": primary["filename"],
            "url": primary["url"],
            "page": "https://modrinth.com/mod/%s" % slug,
            "size": os.path.getsize(dest),
            "sha1": digest,
            "sha1_ok": (primary.get("hashes") or {}).get("sha1") in (None, digest),
            "staged": os.path.relpath(dest, ROOT).replace("\\", "/"),
            **info,
        })
        flag = "REQ" if info["jei_required"] else ("OPT" if info["jei_optional"] else "---")
        print("[%3d/%3d] %-38s %s refs=%-4d %s" % (i, len(slugs), slug, flag,
                                                    info["jei_api_refs"], ",".join(info["modids"])))
        time.sleep(0.1)

    with open(os.path.join(ROOT, "build", "tmp", "modsearch", "staged.json"), "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=1)
    print("staged %d jars" % len(records))


def reinspect():
    """Re-run metadata inspection over already staged jars (no re-download)."""
    path = os.path.join(ROOT, "build", "tmp", "modsearch", "staged.json")
    with open(path, encoding="utf-8") as f:
        records = json.load(f)
    for r in records:
        jar = os.path.join(ROOT, r["staged"])
        r.update(inspect(jar))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=1)
    print("reinspected %d jars" % len(records))


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--reinspect":
        reinspect()
        sys.exit(0)
    if len(sys.argv) > 1 and sys.argv[1] == "--all-candidates":
        with open(os.path.join(ROOT, "build", "tmp", "modsearch", "hits.json"), encoding="utf-8") as f:
            slugs = sorted(json.load(f))
    else:
        slugs = sys.argv[1:]
    main(slugs)
