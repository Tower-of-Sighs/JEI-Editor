"""Iterate: start the dev client, read the load-failure log, drop the offending jar,
repeat until the client reaches the main menu.

Some mods enforce a minimum JEI/NeoForge version in code (a mixin or plugin
constructor) rather than in their metadata, so they only surface at pre-load.
Driving the loop off the real log catches those; the offline verify.py pass
cannot.
"""
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(ROOT, "build", "tmp", "modsearch")
JARS = os.path.join(BASE, "jars")
MODS = os.path.join(ROOT, "targets", "neoforge-1.21.1", "run", "mods")
LOG = os.path.join(BASE, "client.stdout.log")
# The launcher moved with this toolkit; the log it writes stays under BASE.
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run-client.ps1")

# Map the mod id that appears in a failure message to the installed jar.
def jar_for(modid):
    for name in os.listdir(MODS):
        if not name.endswith(".jar"):
            continue
        if name.startswith(modid + "__") or ("__" in name and name.split("__")[0] == modid):
            return name
    # Fall back to reading the jar's declared mod ids.
    import zipfile
    for name in os.listdir(MODS):
        if not name.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(os.path.join(MODS, name)) as z:
                for n in [x for x in z.namelist() if x.endswith("mods.toml")]:
                    text = z.read(n).decode("utf-8-sig", "replace")
                    section = ""
                    for raw in text.splitlines():
                        line = raw.split("#", 1)[0].strip()
                        if line.startswith("["):
                            section = line.strip("[]").strip()
                            continue
                        m = re.match(r'modId\s*=\s*"?([^"]+)"?', line)
                        if m and section.endswith("mods") and m.group(1) == modid:
                            return name
        except Exception:
            pass
    return None


def run_once(timeout):
    if os.path.exists(LOG):
        os.remove(LOG)
    proc = subprocess.Popen(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT,
         "-TimeoutSeconds", str(timeout)],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out, _ = proc.communicate()
    return out


def main():
    max_rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    removed = []
    for round_no in range(1, max_rounds + 1):
        print("=== round %d: %d jars installed" % (round_no, len([f for f in os.listdir(MODS) if f.endswith(".jar")])))
        out = run_once(1500)
        print(out.strip()[-300:])
        text = ""
        if os.path.exists(LOG):
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                text = f.read()

        if "CLIENT REACHED MAIN MENU" in out or "Starting JEI took" in text:
            print("\nSUCCESS: client reached the main menu with the current set.")
            break

        # Collect every mod named in a pre-load / dependency failure.
        offenders = set()
        for pat in (r"Error during pre-loading phase: Mod (\w+) only supports",
                    r"Error during pre-loading phase: Mod (\w+) requires",
                    r"Failed to create mod instance\. ModID: (\w+)",
                    r"Mod ID: '[\w]+', Requested by: '([\w]+)'"):
            offenders.update(re.findall(pat, text))
        # Anything that failed is a candidate; drop the ones we can identify.
        victims = []
        for mid in sorted(offenders):
            name = jar_for(mid)
            if name:
                victims.append((mid, name))
        if not victims:
            print("\n!! no identifiable offender; stopping. Inspect %s" % LOG)
            break
        for mid, name in victims:
            print("  DROP %-24s (%s)" % (mid, name))
            os.remove(os.path.join(MODS, name))
            removed.append((mid, name))

    with open(os.path.join(BASE, "removed_by_runtime.json"), "w", encoding="utf-8") as f:
        import json
        json.dump(removed, f, ensure_ascii=False, indent=1)
    print("\nremoved %d jar(s) in total" % len(removed))


if __name__ == "__main__":
    main()
