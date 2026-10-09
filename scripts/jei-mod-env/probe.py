import json, os, re, sys, time, urllib.request, urllib.parse

UA = {"User-Agent": "jei-editor-research/1.0"}
CACHE = "build/tmp/modsearch/cache"
JEI_ID = "u6dRKJwZ"

def get(url, key):
    p = os.path.join(CACHE, key + ".json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f: return json.load(f)
    req = urllib.request.Request(url, headers=UA)
    for a in range(4):
        try:
            with urllib.request.urlopen(req, timeout=40) as r: d = json.load(r)
            with open(p, "w", encoding="utf-8") as f: json.dump(d, f)
            return d
        except Exception as e:
            if a == 3: return {"__error__": str(e)}
            time.sleep(2 + 2*a)

with open("build/tmp/modsearch/hits.json", encoding="utf-8") as f:
    hits = json.load(f)

out = {}
for slug, h in sorted(hits.items()):
    pid = h["project_id"]
    vl = get(f"https://api.modrinth.com/v2/project/{pid}/version?"
             + urllib.parse.urlencode({"loaders": json.dumps(["neoforge"]),
                                       "game_versions": json.dumps(["1.21.1"])}),
             "ver_" + slug)
    if isinstance(vl, dict):
        out[slug] = {"title": h["title"], "project_id": pid, "downloads": h["downloads"], "error": vl["__error__"]}
        continue
    deps = set()
    for v in vl:
        for d in v.get("dependencies", []):
            if d.get("project_id"): deps.add(d["project_id"])
    out[slug] = {"title": h["title"], "project_id": pid, "downloads": h["downloads"],
                 "description": h["description"],
                 "requires_jei": JEI_ID in deps,
                 "jei_dep_kinds": sorted({d.get("dependency_type") for v in vl for d in v.get("dependencies", []) if d.get("project_id") == JEI_ID}),
                 "n_versions": len(vl)}

with open("build/tmp/modsearch/probe.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)

yes = [s for s, v in out.items() if v.get("requires_jei")]
err = [s for s, v in out.items() if v.get("error")]
print("candidates:", len(out), "requires_jei:", len(yes), "errors:", len(err))
print("\n".join(sorted(yes, key=lambda s: -out[s]["downloads"])))
