#!/usr/bin/env python3
"""Keep the four representations of `daemon-launch.json` from drifting apart.

`<module>/build/compose-previews/daemon-launch.json` is written by the Gradle
plugin and read by the daemon JVM, the CLI doctor, and the VS Code extension.
Nothing in the build knows those are the same file. Each side declares its own
copy of the shape and its own copy of the schema version, and two of them carry
a comment asking a human to remember:

    render-session/subprocess/.../SubprocessRenderSession.kt
        /** ... mirrors the gradle plugin's writer. */
        private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

    cli/.../McpCommand.kt
        // Pinned to DAEMON_DESCRIPTOR_SCHEMA_VERSION in
        // gradle-plugin/.../DaemonClasspathDescriptor.kt. Keep in sync — bump together.
        internal const val EXPECTED_DESCRIPTOR_SCHEMA_VERSION: Int = 2

A comment is not a gate. Bumping the writer alone leaves those two writing and
expecting v2 with nothing failing, and the VS Code reader — the only one that
checks the version at all — rejecting every descriptor the plugin produces
(`daemonProcess.ts`: `parsed.schemaVersion !== DAEMON_DESCRIPTOR_SCHEMA_VERSION`).

This matters for the preview-server split (#3824) beyond ordinary tidiness:
`:render-session-subprocess` is one of the twelve published contract modules an
extracted server links against. Once that is a repo boundary, a bump on one
side and a stale mirror on the other stops being a same-commit mistake and
becomes a cross-repo version skew no compiler sees.

What this checks
----------------

1. **Version agreement** — every declaration of the descriptor schema version
   equals the writer's `DAEMON_DESCRIPTOR_SCHEMA_VERSION`, and every site is
   registered. An unregistered copy fails: a new mirror must be declared here,
   which is the point at which someone can ask whether it should exist at all.

2. **Structural agreement** — for each reader (`DaemonLaunchDescriptor` in
   `:daemon:core`, and the TypeScript interface in the extension):
   every field the reader *requires* is emitted by the writer, shared fields
   carry corresponding types, and any reader-only field is optional. A required
   field the writer never writes is a parse failure at runtime.

3. **`BtaCompileConfig` field-for-field** between Kotlin and TypeScript, which
   its own KDoc claims and nothing enforced.

4. **Unknown-key tolerance** — the JVM reader keeps `ignoreUnknownKeys = true`.
   That is the single line making the writer's `btaCompile` (which the JVM
   reader does not declare) safe rather than fatal.

5. **Sysprop key mirrors** — string constants the descriptor carries that are
   re-declared elsewhere, e.g. `SANDBOX_COUNT_PROP`, whose own KDoc says "both
   sides MUST agree".

Divergences that are correct by design are recorded in
`daemon-launch-schema-allowlist.json` with a reason, in the debt-register style
of `serve-seam-allowlist.json`: an entry is a claim someone made on purpose,
not an exemption to be added when the check goes red.

    python3 scripts/check-daemon-launch-schema.py

Wired into `./gradlew checkDaemonLaunchSchema`, which `check` depends on. Pure
stdlib; unit-tested by scripts/test_check_daemon_launch_schema.py.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST = Path(__file__).resolve().parent / "daemon-launch-schema-allowlist.json"

WRITER = "gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/DaemonClasspathDescriptor.kt"
JVM_READER = "daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/DaemonLaunchDescriptor.kt"
TS_READER = "vscode-extension/src/daemon/daemonProtocol.ts"


def read(rel: str) -> str:
    return (REPO_ROOT / rel).read_text(encoding="utf-8")


def strip_comments(text: str) -> str:
    """Remove comments, keeping string literals intact.

    String-aware on purpose. A stripper that only looks for `//` and `/*` truncates
    `"https://host"` at the slashes, and a literal containing `/*` opens a block comment that
    swallows the rest of the file — which for a repo-wide scanner means a declaration can hide
    behind an ordinary URL. It keeps the contents because mirrored constants ARE strings
    (`"composeai.daemon.sandboxCount"`), so blanking them would defeat the comparison.

    Handles Kotlin raw strings and char literals and TypeScript template literals; Kotlin block
    comments nest, TypeScript's do not, and treating both as nesting is the safe direction (an
    unmatched `*/` cannot then run past the end of the file).
    """
    out: list[str] = []
    i, n, depth = 0, len(text), 0
    while i < n:
        c, two, three = text[i], text[i : i + 2], text[i : i + 3]
        if depth:
            if two == "/*":
                depth += 1
                i += 2
            elif two == "*/":
                depth -= 1
                i += 2
            else:
                i += 1
        elif two == "/*":
            depth = 1
            i += 2
        elif two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif three == '"""':
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c in "\"'`":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def balanced(text: str, open_at: int, opener: str, closer: str) -> str:
    """Body between `opener` at `open_at` and its matching `closer`."""
    i, depth, start = open_at + 1, 1, open_at + 1
    while depth:
        c = text[i]
        depth += (c == opener) - (c == closer)
        i += 1
    return text[start : i - 1]


# --------------------------------------------------------------------------
# Kotlin
# --------------------------------------------------------------------------

KT_FIELD = re.compile(r"\bval\s+(\w+)\s*:\s*(.+)", re.S)


def split_params(body: str) -> list[str]:
    """Split a parameter list on top-level commas.

    Not `split(",")`: `systemProperties: Map<String, String>` carries a comma inside its type
    argument list, and a naive split truncated it to `Map<String` — which then compared unequal
    to the reader's identical declaration and reported drift that did not exist. Depth-tracking
    over `<>`, `()` and `[]` is the whole fix.
    """
    parts, depth, start = [], 0, 0
    for i, c in enumerate(body):
        if c in "<([":
            depth += 1
        elif c in ">)]":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return [p.strip() for p in parts if p.strip()]


def kotlin_data_class(rel: str, name: str) -> dict[str, tuple[str, bool]]:
    """`{field: (type, has_default)}` for a Kotlin `data class` primary constructor."""
    text = strip_comments(read(rel))
    m = re.search(r"\bdata class\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        raise LookupError(f"{name} not found in {rel}")
    fields: dict[str, tuple[str, bool]] = {}
    for param in split_params(balanced(text, m.end() - 1, "(", ")")):
        fm = KT_FIELD.search(param)
        if not fm:
            continue
        rest = fm.group(2)
        # A default value is everything after a top-level `=`.
        depth, cut = 0, None
        for i, c in enumerate(rest):
            if c in "<([":
                depth += 1
            elif c in ">)]":
                depth -= 1
            elif c == "=" and depth == 0:
                cut = i
                break
        type_ = (rest if cut is None else rest[:cut]).strip()
        fields[fm.group(1)] = (re.sub(r"\s+", " ", type_), cut is not None)
    return fields


KT_CONST = re.compile(
    r"(?:public |internal |private )?const val (\w+)\s*(?::\s*\w+\s*)?=\s*(.+?)\s*$", re.M
)


def kotlin_consts(rel: str) -> dict[str, str]:
    return {m.group(1): m.group(2) for m in KT_CONST.finditer(strip_comments(read(rel)))}


# --------------------------------------------------------------------------
# TypeScript
# --------------------------------------------------------------------------

TS_FIELD = re.compile(r"(\w+)(\??)\s*:\s*([^;]+?);")


def ts_interface(rel: str, name: str) -> dict[str, tuple[str, bool]]:
    """`{field: (type, optional)}` for a TS interface. `optional` = declared `field?:`."""
    text = strip_comments(read(rel))
    m = re.search(r"\binterface\s+" + re.escape(name) + r"\s*\{", text)
    if not m:
        raise LookupError(f"interface {name} not found in {rel}")
    body = balanced(text, m.end() - 1, "{", "}")
    return {f.group(1): (f.group(3).strip(), f.group(2) == "?") for f in TS_FIELD.finditer(body)}


TS_CONST = re.compile(r"export const (\w+)\s*(?::\s*\w+\s*)?=\s*(.+?);", re.M)


def ts_consts(rel: str) -> dict[str, str]:
    return {m.group(1): m.group(2).strip() for m in TS_CONST.finditer(strip_comments(read(rel)))}


# --------------------------------------------------------------------------
# Kotlin type -> TypeScript type
# --------------------------------------------------------------------------


def kotlin_to_ts(kt: str) -> str:
    """The TS spelling a Kotlin descriptor field must have. Total over the types in use."""
    kt = kt.strip()
    if kt.endswith("?"):
        return f"{kotlin_to_ts(kt[:-1])} | null"
    if kt in ("Int", "Long", "Double", "Float"):
        return "number"
    if kt == "String":
        return "string"
    if kt == "Boolean":
        return "boolean"
    m = re.fullmatch(r"List<(.+)>", kt)
    if m:
        return f"{kotlin_to_ts(m.group(1))}[]"
    m = re.fullmatch(r"Map<\s*String\s*,\s*(.+)>", kt)
    if m:
        return f"Record<string, {kotlin_to_ts(m.group(1))}>"
    return kt  # a named type: same name on both sides


def ts_types_agree(kt: str, ts: str) -> bool:
    want = kotlin_to_ts(kt)
    norm = lambda s: re.sub(r"\s+", " ", s).strip()
    if norm(want) == norm(ts):
        return True
    # `string | null` and `null | string` are the same type.
    return sorted(p.strip() for p in norm(want).split("|")) == sorted(
        p.strip() for p in norm(ts).split("|")
    )


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def check_versions(allowlist: dict, failures: list[str]) -> None:
    """Every declared copy of the schema version agrees, and every copy is registered."""
    sites = allowlist["schemaVersionSites"]
    writer_version = kotlin_consts(WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]

    for site in sites:
        rel, symbol = site["file"], site["symbol"]
        consts = ts_consts(rel) if rel.endswith(".ts") else kotlin_consts(rel)
        if symbol not in consts:
            failures.append(
                f"  {rel}: `{symbol}` is registered as a schema-version mirror but no longer "
                f"exists.\n    Remove it from `schemaVersionSites` if the mirror is genuinely "
                f"gone — that is progress, not a failure to hide."
            )
            continue
        if consts[symbol] != writer_version:
            failures.append(
                f"  {rel}: `{symbol}` = {consts[symbol]}, but the writer "
                f"({WRITER.split('/')[-1]}) says {writer_version}.\n"
                f"    All copies of the descriptor schema version bump together, or the VS Code "
                f"reader rejects every descriptor the plugin writes."
            )

    registered = {(s["file"], s["symbol"]) for s in sites}
    for found_rel, symbol in discover_version_mirrors():
        if (found_rel, symbol) not in registered:
            failures.append(
                f"  {found_rel}: `{symbol}` looks like another copy of the descriptor schema "
                f"version and is not registered.\n    Add it to `schemaVersionSites` in "
                f"{ALLOWLIST.name} — or better, have it read the writer's constant instead of "
                f"declaring its own."
            )

    # Discovery by use. Whatever a construction site stamps must be a registered constant.
    known_symbols = {s["symbol"] for s in sites} | set(allowlist["versionStampAliases"])
    for found_rel, expr in discover_version_stamps():
        if expr.isdigit():
            failures.append(
                f"  {found_rel}: a descriptor is constructed with `schemaVersion = {expr}`, a bare "
                f"literal.\n    That is a mirror with no name, which no name-based scan can find. "
                f"Stamp a registered constant instead."
            )
        elif expr not in known_symbols:
            failures.append(
                f"  {found_rel}: a descriptor is constructed with `schemaVersion = {expr}`, which "
                f"is neither a registered site nor a recorded alias.\n    Add it to "
                f"`schemaVersionSites` or `versionStampAliases` in {ALLOWLIST.name}."
            )


RAW_KEY = re.compile(r"\bobj\[\"(\w+)\"\]")


def check_raw_key_readers(
    writer: dict[str, tuple[str, bool]], allowlist: dict, failures: list[str]
) -> None:
    """Readers that pull fields out of the raw JSON instead of deserialising a typed DTO.

    `compose-preview doctor` parses the descriptor into a `JsonObject` and indexes it by string.
    Modelling only the two typed readers left it invisible: renaming a field in the writer and
    both DTOs would keep the gate green while doctor went on asking for a key that no longer
    exists — and, because a missing key reads as `null` rather than throwing, silently reporting
    the daemon as disabled rather than failing loudly.
    """
    for rel, spec in allowlist["rawKeyReaders"].items():
        keys = {m.group(1) for m in RAW_KEY.finditer(strip_comments(read(rel)))}
        if not keys:
            failures.append(
                f"  {rel}: registered as a raw-key reader but no `obj[\"…\"]` accesses were "
                f"found. Prune it from `rawKeyReaders`, or update the pattern if the reader "
                f"changed shape."
            )
        for key in sorted(keys - set(writer)):
            failures.append(
                f"  {rel}: reads `{key}` straight out of the descriptor JSON, but the writer emits "
                f"no such field.\n    {spec['why']}"
            )


def wire_fingerprint(writer: dict[str, tuple[str, bool]]) -> str:
    """A digest of the writer's on-the-wire shape: field names, types, and optionality.

    Version agreement between the copies is necessary and not sufficient. Nothing stopped a PR
    from renaming a field in the writer AND both readers in one commit, leaving every constant at
    v2 — in-repo everything agrees, while a released VS Code extension happily accepts the new
    descriptor as v2 and then misreads it. Pinning the shape makes the wire format's identity
    explicit: change it and this fails, which forces the version bump (or a deliberate decision
    that the change is backwards-compatible) to be part of the same diff.
    """
    shape = ";".join(
        f"{name}:{type_}{'?' if has_default else ''}" for name, (type_, has_default) in writer.items()
    )
    return hashlib.sha256(shape.encode("utf-8")).hexdigest()[:16]


def check_wire_fingerprint(
    writer: dict[str, tuple[str, bool]], allowlist: dict, failures: list[str]
) -> None:
    actual = wire_fingerprint(writer)
    recorded = allowlist["wireFingerprint"]
    if actual != recorded["digest"]:
        failures.append(
            f"  the descriptor's wire shape changed: fingerprint {recorded['digest']} -> {actual}, "
            f"recorded against schema v{recorded['schemaVersion']}.\n"
            f"    If the change is breaking for an already-released reader, bump "
            f"`DAEMON_DESCRIPTOR_SCHEMA_VERSION` everywhere and record the new pair here. If it is "
            f"additive and safe (a new field with a default), record the new digest against the "
            f"same version and say so in the PR — but decide, rather than letting the shape drift "
            f"under a version that no longer describes it."
        )
    writer_version = kotlin_consts(WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]
    if str(recorded["schemaVersion"]) != writer_version:
        failures.append(
            f"  `wireFingerprint.schemaVersion` is {recorded['schemaVersion']} but the writer is "
            f"at {writer_version}. Record the fingerprint against the version it describes."
        )


# `*DESCRIPTOR_SCHEMA_VERSION` was the original pattern and it was too narrow: `ServeBundleDaemon`
# calls its copy `DAEMON_LAUNCH_SCHEMA_VERSION` and stamps real descriptors with it, so a fifth
# mirror sat outside a check whose whole claim was that every mirror is registered. Name matching
# is a heuristic either way — `discover_version_stamps` below is the one that cannot be renamed
# out of, and this stays to catch readers that only *compare* a version without constructing one.
VERSION_NAME = re.compile(
    r"(?:const val|export const) (\w*(?:DESCRIPTOR|DAEMON_LAUNCH)_SCHEMA_VERSION)\b"
)

DESCRIPTOR_CTOR = re.compile(r"\b(?:DaemonLaunchDescriptor|DaemonClasspathDescriptor)\s*\(")
SCHEMA_ARG = re.compile(r"\bschemaVersion\s*=\s*([A-Za-z_]\w*|\d+)")


def discover_version_stamps() -> list[tuple[str, str]]:
    """`(file, expression)` for every value stamped as a descriptor's `schemaVersion`.

    Discovery by *use* rather than by name. A mirror is dangerous because it writes a version into
    a real descriptor, not because of what it is called, and the name-based scan above missed
    exactly that case. This finds the construction sites instead: whatever appears as
    `schemaVersion = …` there has to be a registered constant, never a bare literal — a literal is
    a mirror with no name at all, which is the least visible kind.

    Main sources only. Tests construct deliberately-skewed descriptors (a v1 payload to prove the
    reader rejects it), and those are the point of the test rather than drift.
    """
    stamps: list[tuple[str, str]] = []
    for rel, text in walk_sources():
        if "/src/test/" in f"/{rel}" or "/src/functionalTest/" in f"/{rel}":
            continue
        for m in DESCRIPTOR_CTOR.finditer(text):
            try:
                body = balanced(text, m.end() - 1, "(", ")")
            except IndexError:
                continue  # a construction split across an unbalanced fragment; nothing to read
            for arg in SCHEMA_ARG.finditer(body):
                stamps.append((rel, arg.group(1)))
    return stamps


PRUNE = {"build", "node_modules", ".git", ".gradle", "out", "dist", "scripts"}


def walk_sources() -> list[tuple[str, str]]:
    """`(relative path, comment-stripped text)` for every Kotlin/TypeScript source in the repo.

    Prunes whole directories rather than filtering paths after the walk: `node_modules` and the
    per-module `build/` trees hold far more sources than the repo does, and descending into them
    to discard the results costs more than every other check here put together.
    """
    out: list[tuple[str, str]] = []
    for dirpath, dirnames, filenames in os.walk(REPO_ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in PRUNE and not d.startswith("."))
        for filename in sorted(filenames):
            if not filename.endswith((".kt", ".ts")):
                continue
            path = Path(dirpath) / filename
            out.append(
                (
                    path.relative_to(REPO_ROOT).as_posix(),
                    strip_comments(path.read_text(encoding="utf-8")),
                )
            )
    return out


def discover_version_mirrors() -> list[tuple[str, str]]:
    """Any descriptor schema-version constant declared anywhere in the tree."""
    return [
        (rel, m.group(1)) for rel, text in walk_sources() for m in VERSION_NAME.finditer(text)
    ]


def check_reader(
    label: str,
    rel: str,
    reader: dict[str, tuple[str, bool]],
    writer: dict[str, tuple[str, bool]],
    allowlist: dict,
    failures: list[str],
    ts: bool,
) -> None:
    reader_only = allowlist["readerOnlyFields"].get(label, {})

    for field, (rtype, optional) in reader.items():
        if field not in writer:
            if field not in reader_only:
                failures.append(
                    f"  {rel}: `{field}` is declared by the {label} reader but the writer never "
                    f"emits it.\n    Either the writer should, or record it in `readerOnlyFields` "
                    f"with what does write it."
                )
            elif not optional:
                failures.append(
                    f"  {rel}: `{field}` is written only by "
                    f"{reader_only[field]['writtenBy']}, so a plugin-written descriptor omits it "
                    f"— but it is declared without a default. Parsing one would fail."
                )
            continue

        wtype = writer[field][0]
        agree = ts_types_agree(wtype, rtype) if ts else wtype == rtype
        if not agree:
            want = kotlin_to_ts(wtype) if ts else wtype
            failures.append(
                f"  {rel}: `{field}` is `{rtype}` in the {label} reader but `{wtype}` in the "
                f"writer (expected `{want}`)."
            )

    for field, (wtype, has_default) in writer.items():
        if field in reader:
            continue
        tolerated = allowlist["writerOnlyFields"].get(field)
        if not tolerated or label not in tolerated["invisibleTo"]:
            failures.append(
                f"  {rel}: the writer emits `{field}: {wtype}` and the {label} reader does not "
                f"declare it.\n    Add it, or record it in `writerOnlyFields` naming the readers "
                f"that legitimately ignore it."
            )


def check_unknown_key_tolerance(allowlist: dict, failures: list[str]) -> None:
    """The JVM reader ignores unknown keys — what makes writer-only fields survivable."""
    if not allowlist["writerOnlyFields"]:
        return
    text = strip_comments(read(JVM_READER))
    if not re.search(r"Json\s*\{[^}]*ignoreUnknownKeys\s*=\s*true", text, re.S):
        failures.append(
            f"  {JVM_READER}: `ignoreUnknownKeys = true` is gone from the descriptor's `Json`.\n"
            f"    The writer emits "
            + ", ".join(f"`{f}`" for f in sorted(allowlist["writerOnlyFields"]))
            + " which this reader does not declare; without that setting every plugin-written "
            "descriptor fails to parse."
        )


def check_mirrored_constants(allowlist: dict, failures: list[str]) -> None:
    """String constants the descriptor carries that other modules re-declare."""
    for name, spec in allowlist["mirroredConstants"].items():
        source = kotlin_consts(spec["declaredBy"]).get(name)
        if source is None:
            failures.append(
                f"  {spec['declaredBy']}: `{name}` is registered as the source of a mirrored "
                f"constant but is not declared there."
            )
            continue
        for rel in spec["mirroredBy"]:
            value = kotlin_consts(rel).get(spec.get("mirrorSymbol", name))
            if value is None:
                failures.append(
                    f"  {rel}: registered as mirroring `{name}` but no longer declares it. "
                    f"Prune it from `mirroredConstants`."
                )
            elif value != source:
                failures.append(
                    f"  {rel}: `{name}` = {value}, but {spec['declaredBy']} says {source}. "
                    f"{spec['why']}"
                )


def check() -> int:
    allowlist = json.loads(ALLOWLIST.read_text(encoding="utf-8"))
    failures: list[str] = []

    writer = kotlin_data_class(WRITER, "DaemonClasspathDescriptor")

    check_versions(allowlist, failures)
    check_reader(
        "jvm",
        JVM_READER,
        kotlin_data_class(JVM_READER, "DaemonLaunchDescriptor"),
        writer,
        allowlist,
        failures,
        ts=False,
    )
    check_reader(
        "vscode",
        TS_READER,
        ts_interface(TS_READER, "DaemonLaunchDescriptor"),
        writer,
        allowlist,
        failures,
        ts=True,
    )
    check_unknown_key_tolerance(allowlist, failures)
    check_mirrored_constants(allowlist, failures)
    check_raw_key_readers(writer, allowlist, failures)
    check_wire_fingerprint(writer, allowlist, failures)

    # `BtaCompileConfig` claims to be field-for-field across the two languages.
    kt_bta = kotlin_data_class(WRITER, "BtaCompileConfig")
    ts_bta = ts_interface(TS_READER, "BtaCompileConfig")
    if list(kt_bta) != list(ts_bta):
        failures.append(
            f"  BtaCompileConfig is not field-for-field across the two languages.\n"
            f"    kotlin: {list(kt_bta)}\n    typescript: {list(ts_bta)}"
        )
    else:
        for field, (ktype, _) in kt_bta.items():
            if not ts_types_agree(ktype, ts_bta[field][0]):
                failures.append(
                    f"  BtaCompileConfig.{field}: `{ktype}` in Kotlin vs "
                    f"`{ts_bta[field][0]}` in TypeScript "
                    f"(expected `{kotlin_to_ts(ktype)}`)."
                )

    if failures:
        print("check-daemon-launch-schema: FAILED", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        return 1

    sites = len(allowlist["schemaVersionSites"])
    print(
        f"check-daemon-launch-schema: OK — {len(writer)} writer field(s), 2 readers agree, "
        f"{sites} schema-version site(s) at v"
        + kotlin_consts(WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]
    )
    return 0


if __name__ == "__main__":
    sys.exit(check())
