import json, os, re, sys, time, urllib.request, urllib.parse

UA={"User-Agent":"jei-editor-research/1.0"}
CACHE="build/tmp/modsearch/cache"
JEI="u6dRKJwZ"
IGNORE_IDS={"u6dRKJwZ"}  # jei
IGNORE_SLUGS={"minecraft","neoforge","forge","fabric-api","fabricloader","fabric","architectury-api",
              "cloth-config","kotlin-for-forge","balm","puzzles-lib","bookshelf","common-network",
              "resourceful-lib","geckolib","curios","jade","moonlight","selene","placebo","botarium"}

def get(url,key):
    p=os.path.join(CACHE,key+".json")
    if os.path.exists(p):
        with open(p,encoding="utf-8") as f: return json.load(f)
    req=urllib.request.Request(url,headers=UA)
    for a in range(4):
        try:
            with urllib.request.urlopen(req,timeout=40) as r: d=json.load(r)
            with open(p,"w",encoding="utf-8") as f: json.dump(d,f)
            return d
        except Exception as e:
            if a==3: return {"__error__":str(e)}
            time.sleep(2+2*a)

def project(pid):
    return get("https://api.modrinth.com/v2/project/%s"%pid,"proj_"+pid)

with open("build/tmp/modsearch/probe.json",encoding="utf-8") as f: probe=json.load(f)
cands=[s for s,v in probe.items() if v.get("requires_jei")]

report={}
for slug in sorted(cands):
    pid=probe[slug]["project_id"]
    versions=get("https://api.modrinth.com/v2/project/%s/version?"%pid+urllib.parse.urlencode(
        {"loaders":json.dumps(["neoforge"]),"game_versions":json.dumps(["1.21.1"])}),"ver_"+slug)
    # newest release-ish version's deps
    pool=sorted(versions,key=lambda v:v.get("date_published",""),reverse=True)
    req=set(); incompatible=set()
    for v in pool[:3]:
        for d in v.get("dependencies",[]):
            t=d.get("dependency_type"); tp=d.get("project_id")
            if not tp: continue
            if t=="required": req.add(tp)
            if t=="incompatible": incompatible.add(tp)
    def names(ids):
        out=[]
        for i in ids:
            if i in IGNORE_IDS: continue
            p=project(i)
            if isinstance(p,dict) and "__error__" not in p:
                if p.get("slug") in IGNORE_SLUGS: continue
                out.append(p.get("slug") or p.get("title"))
            else: out.append(i)
        return sorted(set(out))
    report[slug]={"title":probe[slug]["title"],"downloads":probe[slug]["downloads"],
                  "required":names(req),"incompatible":names(incompatible),
                  "description":probe[slug].get("description","")}
    time.sleep(0.15)

with open("build/tmp/modsearch/deps.json","w",encoding="utf-8") as f: json.dump(report,f,ensure_ascii=False,indent=1)

pure=[s for s,v in report.items() if not v["required"] and "just-enough-items" not in v["incompatible"] and not v["incompatible"]]
needy={s:v for s,v in report.items() if v["required"]}
bad={s:v for s,v in report.items() if v["incompatible"]}
print("PURE JEI addons (%d):"%len(pure))
for s in sorted(pure,key=lambda s:-report[s]["downloads"]): print("  ",s,"|",report[s]["title"][:45])
print()
print("REQUIRE OTHER MODS (%d):"%len(needy))
for s,v in sorted(needy.items(),key=lambda kv:-kv[1]["downloads"]): print("  ",s,"->",", ".join(v["required"])[:90])
print()
print("INCOMPATIBLE/EMI-centric (%d):"%len(bad))
for s,v in bad.items(): print("  ",s,"incompatible:",v["incompatible"],"required:",v["required"])
