#!/usr/bin/env bash
# Decide which modules a release actually has to publish.
#
# Every release publishes all 26 coordinates at the tag, whether or not a byte of them changed.
# This repository releases roughly 123 times in 30 days, which at 20 files per coordinate-publish
# is ~66,000 files a month against an org-wide Maven Central file-count limit — the largest single
# contributor across the four repositories that publish (yschimke/compose-ai-tools#4772).
#
# A module is published when:
#
#   1. a file under it changed since the tag IT last published at (not since the last release —
#      a module skipped for three releases is compared against its own baseline, so nothing is
#      ever missed by a gap), or
#   2. a module it depends on is being published, or
#   3. a shared build input changed, which can move every artifact at once.
#
# Rule 2 is what keeps the POMs honest, and it is deliberately coarser than it needs to be. A
# published POM names its project dependencies at *their* `project.version`, so a module may only
# be skipped while everything it depends on is also skipped; otherwise it would name a sibling
# version that was never uploaded — exactly the break this repository shipped in v2.2.1 and paid
# for in yschimke/wear-m3-catalog#350.
#
# ## The `gradle-plugin` included build
#
# Its four coordinates are handled as one unit: if anything under `gradle-plugin/` changed, all
# four publish. They are not `subprojects` of this build, so the settings walk below cannot see
# their inter-module dependencies. Publishing them together is cheap and avoids maintaining a
# second project graph parser.
#
# Every uncertainty resolves to "publish". Central refuses a second upload of a version, so an
# unnecessary publish costs quota while a wrongly-skipped one is unrepairable.
#
# Usage: maven-publish-plan.sh --head <ref> [--manifest <path>]
# Output: one artifact id per line, on stdout. Diagnostics go to stderr.
set -euo pipefail

HEAD_REF=""
MANIFEST=""
WRITE_MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --head) HEAD_REF="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --write-manifest) WRITE_MANIFEST="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HEAD_REF" ] || { echo "--head is required" >&2; exit 2; }
[ -z "$MANIFEST" ] || [ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 2; }

python3 - "$HEAD_REF" "$MANIFEST" "$WRITE_MANIFEST" <<'PY'
import json, re, subprocess, sys, collections, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

GROUP_PATH = "ee/schimke/composeai"
CENTRAL = "https://repo1.maven.org/maven2"

head, manifest_path, write_manifest_path = sys.argv[1], sys.argv[2], sys.argv[3]

def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout

settings = open("settings.gradle.kts", encoding="utf-8").read()
dirs = dict(re.findall(r'project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)', settings))
paths = re.findall(r'^include\("(:[^"]+)"\)', settings, re.M)

modules = {}   # artifactId -> directory
deps = {}      # artifactId -> [artifactId]
path_to_id = {}
for p in paths:
    d = dirs.get(p, p.lstrip(":").replace(":", "/"))
    try:
        text = open(d + "/build.gradle.kts", encoding="utf-8").read()
    except OSError:
        continue
    if 'composeai.maven-publishing")' not in text:
        continue
    aid = p.lstrip(":").replace(":", "-")
    modules[aid] = d
    path_to_id[p] = aid
    deps[aid] = [m.lstrip(":").replace(":", "-")
                 for m in re.findall(r'project\("(:[^"]+)"\)', text)]
deps = {a: [d for d in ds if d in modules] for a, ds in deps.items()}

# The four `gradle-plugin` coordinates, keyed to the one directory that decides them all. See the
# header: they are an included build, so their project graph is not visible here.
INCLUDED_BUILD_DIR = "gradle-plugin"
INCLUDED_BUILD_IDS = [
    "compose-preview-config",
    "compose-preview-plugin",
    "daemon-launch-builder",
    "preview-discovery",
]
for aid in INCLUDED_BUILD_IDS:
    modules.setdefault(aid, INCLUDED_BUILD_DIR)

def central_release(aid):
    """The newest version of `aid` on Central, or None if it has never published there.

    `<release>` rather than `<latest>`: `latest` can name a snapshot on repositories that carry
    them, and a baseline that is not a real release would diff against a tag that does not exist.
    """
    url = f"{CENTRAL}/{GROUP_PATH}/{aid}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            body = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None  # never published
        print(f"  {aid}: Central said {e.code}; publishing", file=sys.stderr)
        return None
    except Exception as e:  # noqa: BLE001 - any failure resolves to "publish"
        print(f"  {aid}: could not reach Central ({e}); publishing", file=sys.stderr)
        return None
    m = re.search(r"<release>([^<]+)</release>", body)
    return m.group(1) if m else None


if manifest_path:
    recorded = json.load(open(manifest_path, encoding="utf-8"))["modules"]
    print(f"  baseline: {manifest_path} ({len(recorded)} entries)", file=sys.stderr)
else:
    # Eight at a time: 69 sequential round-trips is most of this script's wall clock, and Central
    # serves these as static files.
    with ThreadPoolExecutor(max_workers=8) as pool:
        found = dict(zip(sorted(modules), pool.map(central_release, sorted(modules))))
    recorded = {aid: v for aid, v in found.items() if v}
    print(f"  baseline: Maven Central ({len(recorded)} of {len(modules)} coordinates)",
          file=sys.stderr)

if write_manifest_path:
    with open(write_manifest_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "_comment": "The version each coordinate is published at on Maven Central. "
                            "Resolved at release time by .github/scripts/maven-publish-plan.sh "
                            "and NOT committed - Central is the source of truth.",
                "modules": dict(sorted(recorded.items())),
            },
            f,
            indent=2,
        )
        f.write("\n")


# A shared build input can change any artifact, so it opens the gate for everything.
SHARED = re.compile(r"^(build-logic/|gradle/|gradlew|settings\.gradle\.kts$|build\.gradle\.kts$)")

def changed_since(version, directory):
    """Did `directory` move between the tag for `version` and head?"""
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        print(f"  {directory}: no tag {tag}; publishing", file=sys.stderr)
        return True
    out = git("diff", "--name-only", f"{tag}..{head}", "--", directory)
    return bool(out.strip())

shared_changed = False
for version in sorted(set(recorded.values())):
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        shared_changed = True
        break
    files = [f for f in git("diff", "--name-only", f"{tag}..{head}").split("\n") if f]
    if any(SHARED.match(f) for f in files):
        shared_changed = True
        break

if shared_changed:
    print("  a shared build input changed; publishing every module", file=sys.stderr)
    for aid in sorted(modules):
        print(aid)
    sys.exit(0)

dirty = set()
for aid, directory in modules.items():
    if aid not in recorded:
        print(f"  {aid}: never published; publishing", file=sys.stderr)
        dirty.add(aid)
    elif changed_since(recorded[aid], directory):
        dirty.add(aid)

# Rule 2: anything depending on a dirty module is dirty too, transitively.
rev = collections.defaultdict(set)
for aid, ds in deps.items():
    for d in ds:
        rev[d].add(aid)
stack = list(dirty)
while stack:
    m = stack.pop()
    for r in rev.get(m, ()):
        if r not in dirty:
            dirty.add(r)
            stack.append(r)

print(f"  {len(dirty)} of {len(modules)} modules publish", file=sys.stderr)
for aid in sorted(dirty):
    print(aid)
PY
