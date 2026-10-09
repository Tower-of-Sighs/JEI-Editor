import json, os, re, sys, time, urllib.request, urllib.parse

UA = {"User-Agent": "jei-editor-research/1.0 (github.com/local)"}
CACHE = "build/tmp/modsearch/cache"
os.makedirs(CACHE, exist_ok=True)

def get(url, cache_key):
    path = os.path.join(CACHE, cache_key + ".json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(url, headers=UA)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                data = json.load(r)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return data
        except Exception as e:
            if attempt == 3: raise
            time.sleep(2 + attempt * 3)

LOADER_FACET = json.dumps([["categories:neoforge"], ["versions:1.21.1"]])

def search(query, offset=0):
    url = "https://api.modrinth.com/v2/search?" + urllib.parse.urlencode(
        {"query": query, "facets": LOADER_FACET, "limit": 100, "offset": offset})
    return get(url, "search_" + re.sub(r"[^a-z0-9]+", "_", query.lower()) + "_" + str(offset))

terms = [t for t in sys.argv[1:] if not t.startswith("--")]
hits = {}
for t in terms:
    off = 0
    while True:
        d = search(t, off)
        for h in d["hits"]:
            hits.setdefault(h["slug"], h)
        off += 100
        if off >= d["total_hits"] or off >= 500:
            break
        time.sleep(0.4)
    print(f"  {t}: total={d['total_hits']} union={len(hits)}", file=sys.stderr)

with open("build/tmp/modsearch/hits.json", "w", encoding="utf-8") as f:
    json.dump(hits, f, ensure_ascii=False, indent=1)
print(f"union hits: {len(hits)}", file=sys.stderr)
