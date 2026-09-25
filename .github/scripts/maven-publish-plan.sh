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
#   3. a shared build input changed, which can move every artifact at once, or
#   4. the version catalog changed an entry its own build script uses (see SHARED below: a catalog
#      entry that a shared build file uses is rule 3 instead, and publishes everything).
#
# Rules 3 and 4 skip `build-logic/src/test/**` and whole-line comment/whitespace edits to shared
# Kotlin files (#5576). Tested by `test-maven-publish-plan.sh`.
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
import json, os, re, subprocess, sys, collections, urllib.error, urllib.request
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


# A shared build input can change any artifact, so it opens the gate for everything — with three
# narrowings, each of which falls back to "everything" whenever it is unsure (#5576):
#
#   a. test-only paths under build-logic/ never reach a published artifact;
#   b. an edit to a shared Kotlin file that only adds, removes or re-indents `//` comment lines and
#      blank lines leaves every artifact byte-identical;
#   c. a version-catalog change publishes the modules whose build scripts use a changed entry,
#      rather than all of them — POMs name catalog versions, so those consumers must still publish.
SHARED = re.compile(r"^(build-logic/|gradle/|gradlew|settings\.gradle\.kts$|build\.gradle\.kts$)")
NOT_SHARED = re.compile(r"^build-logic/src/(test|testFixtures|functionalTest|integrationTest)/")
SHARED_KOTLIN = re.compile(r"^(build-logic/.*\.kts?|settings\.gradle\.kts|build\.gradle\.kts)$")
CATALOG = "gradle/libs.versions.toml"

def show(rev, path):
    """`path` at `rev`, or None when it does not exist there."""
    r = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None

def code_lines(text):
    """The lines of a Kotlin file with blank lines, `//` comment lines and indentation dropped."""
    return [s for s in (line.strip() for line in text.splitlines()) if s and not s.startswith("//")]

def comment_only(tag, path):
    """Did `path` change between `tag` and head in whole-line `//` comments and whitespace only?

    Deliberately narrow. A trailing `// comment` after code, a `/* block */` comment and anything
    else count as a real change. A raw string (`\"\"\"`) is the one place a line starting with `//`
    is not a comment, so a file containing one is never judged comment-only.
    """
    old, new = show(tag, path), show(head, path)
    if old is None or new is None or '"""' in old or '"""' in new:
        return False
    return code_lines(old) == code_lines(new)

def load_catalog(rev):
    raw = show(rev, CATALOG)
    if raw is None:
        return None
    try:
        import tomllib
        data = tomllib.loads(raw)
    except Exception:  # noqa: BLE001 - no tomllib, or a catalog it cannot read: publish everything
        return None
    if set(data) - {"versions", "libraries", "plugins", "bundles"}:
        return None
    if not all(isinstance(v, dict) for v in data.values()):
        return None
    return data

def version_ref(entry):
    if isinstance(entry, dict) and isinstance(entry.get("version"), dict):
        return entry["version"].get("ref")
    return None

def catalog_changes(tag):
    """Every catalog entry that moved between `tag` and head, or None when that is not certain.

    Returned as accessor paths below `libs.`: `foo-bar`, `plugins.foo`, `bundles.foo`,
    `versions.foo`. A changed version ref moves every library and plugin that uses it; a changed
    library moves every bundle that contains it.
    """
    old, new = load_catalog(tag), load_catalog(head)
    if old is None or new is None:
        return None
    ov, nv = old.get("versions", {}), new.get("versions", {})
    refs = {k for k in set(ov) | set(nv) if ov.get(k) != nv.get(k)}
    changed = {f"versions.{k}" for k in refs}
    moved_libs = set()
    for section, prefix in (("libraries", ""), ("plugins", "plugins.")):
        o, n = old.get(section, {}), new.get(section, {})
        for k in set(o) | set(n):
            if o.get(k) != n.get(k) or version_ref(o.get(k)) in refs or version_ref(n.get(k)) in refs:
                changed.add(prefix + k)
                if section == "libraries":
                    moved_libs.add(k)
    o, n = old.get("bundles", {}), new.get("bundles", {})
    for k in set(o) | set(n):
        if o.get(k) != n.get(k) or set(o.get(k) or ()) & moved_libs or set(n.get(k) or ()) & moved_libs:
            changed.add(f"bundles.{k}")
    return changed

def reference_patterns(entries):
    """Regexes that find a use of any of `entries` in a build script.

    Both the generated accessor (`libs.foo.bar`, with `-` and `_` mapped to `.` as Gradle does) and
    the alias as a string (`findLibrary("foo-bar")`, `findVersion("foo")`). Case-insensitive, and a
    longer alias sharing a prefix also matches: both only ever over-publish.
    """
    pats = []
    for e in sorted(entries):
        dotted = re.sub(r"[-_.]", ".", e)
        pats.append(re.compile(r"\blibs\." + re.escape(dotted) + r"(?![A-Za-z0-9_])", re.I))
        name = e.split(".", 1)[1] if e.split(".", 1)[0] in ("versions", "plugins", "bundles") else e
        pats.append(re.compile('"' + r"[-_.]".join(map(re.escape, re.split(r"[-_.]", name))) + '"', re.I))
    return pats

def normalise_script(text):
    # ktfmt may break an accessor chain across lines; `libs\n  .foo` is `libs.foo`.
    return re.sub(r"\s*\.\s*", ".", text)

def references(text, pats):
    text = normalise_script(text)
    return next((p.pattern for p in pats if p.search(text)), None)

def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""

def shared_catalog_use(pats):
    """The shared build file that uses a changed catalog entry, if any. That entry can reach every
    module (a convention plugin's dependency, a plugin on the root classpath), so it publishes all."""
    files = ["settings.gradle.kts", "build.gradle.kts"]
    for root, dirnames, names in os.walk("build-logic"):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
        files += [os.path.join(root, n) for n in names if n.endswith((".kt", ".kts"))]
    for f in files:
        if NOT_SHARED.match(f):
            continue
        text = read(f)
        if f == "settings.gradle.kts":
            # `version("compose-remote", "1.0.0-SNAPSHOT")` in a catalog builder OVERRIDES that
            # entry (snapshot mode only); it is a write, not a use of the catalog's value.
            text = re.sub(r'\bversion\(\s*"[^"]*"\s*,', "version(", text)
        hit = references(text, pats)
        if hit:
            return f"{f} ({hit})"
    return None

script_cache = {}
def module_scripts(directory):
    if directory not in script_cache:
        texts = []
        for root, dirnames, names in os.walk(directory):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", "node_modules")]
            texts += [read(os.path.join(root, n)) for n in names if n.endswith(".gradle.kts")]
        script_cache[directory] = "\n".join(texts)
    return script_cache[directory]

def uses_catalog_change(directory, pats):
    text = module_scripts(directory)
    # A script that reads a TOML file itself (a path in a string literal) is outside what the
    # accessor scan can see. A mention in a comment is not a read, and would dirty every catalog
    # change for no reason.
    return re.search(r'\.toml"', text) is not None or references(text, pats) is not None

def shared_verdict(tag, files):
    """(publish everything?, catalog patterns to test each module against)."""
    pats = None
    for f in files:
        if not SHARED.match(f):
            continue
        if NOT_SHARED.match(f):
            print(f"  {tag}: {f} is test-only; not a shared input", file=sys.stderr)
            continue
        if f == CATALOG:
            changes = catalog_changes(tag)
            if changes is None:
                print(f"  {tag}: could not diff {CATALOG}; publishing every module", file=sys.stderr)
                return True, None
            print(f"  {tag}: catalog entries changed: {', '.join(sorted(changes)) or '<none>'}",
                  file=sys.stderr)
            pats = reference_patterns(changes)
            hit = shared_catalog_use(pats) if pats else None
            if hit:
                print(f"  {tag}: a changed catalog entry is used by {hit}; publishing every module",
                      file=sys.stderr)
                return True, None
            continue
        if SHARED_KOTLIN.match(f) and comment_only(tag, f):
            print(f"  {tag}: {f} changed only in comments or whitespace", file=sys.stderr)
            continue
        print(f"  {tag}: shared build input {f} changed", file=sys.stderr)
        return True, None
    return False, pats

def changed_since(version, directory):
    """Did `directory` move between the tag for `version` and head?"""
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        print(f"  {directory}: no tag {tag}; publishing", file=sys.stderr)
        return True
    out = git("diff", "--no-renames", "--name-only", f"{tag}..{head}", "--", directory)
    return bool(out.strip())

shared_changed = False
catalog_pats = {}  # baseline version -> patterns for the catalog entries changed since it
for version in sorted(set(recorded.values())):
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        shared_changed = True
        break
    files = [f for f in git("diff", "--no-renames", "--name-only", f"{tag}..{head}").split("\n") if f]
    shared_changed, pats = shared_verdict(tag, files)
    if shared_changed:
        break
    if pats:
        catalog_pats[version] = pats

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
    elif recorded[aid] in catalog_pats and uses_catalog_change(directory, catalog_pats[recorded[aid]]):
        print(f"  {aid}: uses a changed catalog entry; publishing", file=sys.stderr)
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
