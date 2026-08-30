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
of the retired `serve-seam-allowlist.json`: an entry is a claim someone made on purpose,
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
# The JVM reader moved to yschimke/compose-preview-contracts with the wire contracts, and is
# resolved the same way as the TypeScript one below. Same reasoning: it is one of the three copies
# of this schema, so dropping it would retire half the check rather than relocate it.
JVM_READER_REL = (
    "daemon/protocol/src/main/kotlin/ee/schimke/composeai/daemon/protocol/DaemonLaunchDescriptor.kt"
)
# The TypeScript reader moved to yschimke/compose-preview-vscode with the extension. It is still
# one of the three copies of this schema — and the only reader that *gates* on the version — so
# dropping it from this checker would retire the check that matters most, on the copy furthest from
# the writer.
#
# It is resolved from a checkout instead: `COMPOSE_PREVIEW_VSCODE_ROOT` (what CI sets), then a
# sibling `../compose-preview-vscode`. When neither is present the TypeScript half SKIPS and the
# Kotlin half still runs, because "the other repository is not checked out" is not a drift signal
# and this script is wired into `check`, which every contributor runs.
TS_READER_REL = "src/daemon/daemonProtocol.ts"

# The production image's Dockerfile, which passes the sandbox-count key as a literal in
# JAVA_TOOL_OPTIONS — a mirror of a Kotlin constant that no Kotlin scan can see. It left this
# repository with the preview server (yschimke/compose-preview-server owns the image and publishes
# it), so it resolves from a checkout exactly as the two readers above do. A missing checkout SKIPS
# that mirror rather than failing, for the same reason: "the other repository is not checked out"
# is not a drift signal, and this script runs inside `check`.
SERVER_DOCKERFILE_REL = "deploy/image/Dockerfile"


def contracts_root() -> Path | None:
    """Root of a compose-preview-contracts checkout, or None when there is none."""
    explicit = os.environ.get("COMPOSE_PREVIEW_CONTRACTS_ROOT", "").strip()
    if explicit:
        root = Path(explicit).resolve()
        if not (root / JVM_READER_REL).is_file():
            raise SystemExit(
                f"COMPOSE_PREVIEW_CONTRACTS_ROOT={explicit} does not contain {JVM_READER_REL}"
            )
        return root
    sibling = REPO_ROOT.parent / "compose-preview-contracts"
    return sibling if (sibling / JVM_READER_REL).is_file() else None


def ts_root() -> Path | None:
    """Root of a compose-preview-vscode checkout, or None when there is none."""
    explicit = os.environ.get("COMPOSE_PREVIEW_VSCODE_ROOT", "").strip()
    if explicit:
        root = Path(explicit).resolve()
        if not (root / TS_READER_REL).is_file():
            raise SystemExit(
                f"COMPOSE_PREVIEW_VSCODE_ROOT={explicit} does not contain {TS_READER_REL}"
            )
        return root
    sibling = REPO_ROOT.parent / "compose-preview-vscode"
    return sibling if (sibling / TS_READER_REL).is_file() else None


def server_root() -> Path | None:
    """Root of a compose-preview-server checkout, or None when there is none."""
    explicit = os.environ.get("COMPOSE_PREVIEW_SERVER_ROOT", "").strip()
    if explicit:
        root = Path(explicit).resolve()
        if not (root / SERVER_DOCKERFILE_REL).is_file():
            raise SystemExit(
                f"COMPOSE_PREVIEW_SERVER_ROOT={explicit} does not contain {SERVER_DOCKERFILE_REL}"
            )
        return root
    sibling = REPO_ROOT.parent / "compose-preview-server"
    return sibling if (sibling / SERVER_DOCKERFILE_REL).is_file() else None


_ts_root = ts_root()
TS_READER = TS_READER_REL if _ts_root is not None else ""

_server_root = server_root()

_contracts_root = contracts_root()
JVM_READER = JVM_READER_REL if _contracts_root is not None else ""


def resolve(rel: str) -> Path | None:
    """Map a path from this script or the allowlist onto disk.

    Two of the three copies now live elsewhere. A `.ts` path is the VS Code extension's and a
    `.kt` path is this repository's — except the JVM reader, which moved to
    compose-preview-contracts with the wire contracts. Each resolves against the checkout its
    `*_root()` found, and is None when there is none, so a missing sibling SKIPS that half rather
    than failing: "the other repository is not checked out" is not a drift signal, and this script
    is wired into `check`, which every contributor runs.
    """
    if rel.endswith(".ts"):
        root = _ts_root
        return (root / rel) if root is not None else None
    if rel == JVM_READER_REL:
        return (_contracts_root / rel) if _contracts_root is not None else None
    if rel == SERVER_DOCKERFILE_REL:
        return (_server_root / rel) if _server_root is not None else None
    return REPO_ROOT / rel


def available(rel: str) -> bool:
    """Whether `rel` is present in this checkout.

    False only for a path that lives in another repository whose checkout is absent — the
    TypeScript reader without a compose-preview-vscode checkout, or anything under the wire
    contracts without a compose-preview-contracts one. Callers that walk allowlist-supplied paths
    use this to skip rather than crash, for the same reason the reader checks are guarded: "the
    other repository is not checked out" is not a drift signal, and this script runs inside
    `check`, which every contributor runs.

    CI is where the cross-repo half must be real, and it checks both repositories out; the
    schema-gate step there fails if any test reports a skip.
    """
    return resolve(rel) is not None


def read(rel: str) -> str:
    path = resolve(rel)
    if path is None:
        raise FileNotFoundError(
            f"{rel} lives in another repository; set COMPOSE_PREVIEW_VSCODE_ROOT (.ts), "
            "COMPOSE_PREVIEW_CONTRACTS_ROOT (the JVM reader) or "
            "COMPOSE_PREVIEW_SERVER_ROOT (the production image)"
        )
    return path.read_text(encoding="utf-8")


def stripped(rel: str) -> str:
    """`read` with comments removed under the rules of the file's own language."""
    return strip_comments(read(rel), nested=not rel.endswith(".ts"))


def strip_comments(text: str, nested: bool = True) -> str:
    """Remove comments, keeping string literals intact.

    String-aware on purpose. A stripper that only looks for `//` and `/*` truncates
    `"https://host"` at the slashes, and a literal containing `/*` opens a block comment that
    swallows the rest of the file — which for a repo-wide scanner means a declaration can hide
    behind an ordinary URL. It keeps the contents because mirrored constants ARE strings
    (`"composeai.daemon.sandboxCount"`), so blanking them would defeat the comparison.

    Handles Kotlin raw strings and char literals and TypeScript template literals. `nested`
    selects the language's block-comment rule and is not cosmetic: Kotlin's `/*` nests, TypeScript's
    does not. Applying Kotlin's rule to TypeScript means `/* showing /* */` leaves the scanner
    inside a comment after the real closer and swallows whatever follows — which for a repo-wide
    scanner is a place a mirror could hide.
    """
    out: list[str] = []
    i, n, depth = 0, len(text), 0
    while i < n:
        c, two, three = text[i], text[i : i + 2], text[i : i + 3]
        if depth:
            if two == "/*" and nested:
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
            if not nested:
                j = text.find("*/", i)
                i = len(text) if j < 0 else j + 2
                depth = 0
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

# `@SerialName("x")` changes the JSON key while the Kotlin identifier stays put, so a parser that
# reads identifiers would see no change at all — the structural comparison and the fingerprint would
# both hold while the emitted wire name moved under every reader. None of these DTOs uses it today;
# rather than half-support it, the check refuses it and says why.
KT_SERIAL_NAME = re.compile(r'@SerialName\s*\(\s*"([^"]*)"\s*\)')

# Every rule here reads the Kotlin declaration, so any annotation that changes what
# kotlinx.serialization actually emits is invisible to all of them. `@SerialName` moves the key;
# `@Transient` removes the field entirely; `@EncodeDefault` changes whether a defaulted field is
# written at all. Rather than adding a rule per annotation as each is thought of, nothing is
# allowed on these properties unless it is listed here — so the next one has to be considered
# rather than slipping through.
KT_PROPERTY_ANNOTATION = re.compile(r"@(\w+)")
ALLOWED_DTO_ANNOTATIONS: frozenset[str] = frozenset()


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


def serial_name_renames(rel: str, name: str) -> list[str]:
    """Fields of `name` carrying `@SerialName`, whose wire key differs from their identifier."""
    text = stripped(rel)
    m = re.search(r"\bdata class\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return []
    found = []
    for param in split_params(balanced(text, m.end() - 1, "(", ")")):
        rename = KT_SERIAL_NAME.search(param)
        field = KT_FIELD.search(param)
        if rename and field and rename.group(1) != field.group(1):
            found.append(f"{field.group(1)} -> \"{rename.group(1)}\"")
    return found


CLASS_SERIALIZABLE = re.compile(r"@Serializable\s*\(([^)]*)\)\s*(?:public\s+)?data class\s+(\w+)")
BODY_PROPERTY = re.compile(r"^\s*(?:public|internal|private)?\s*(?:val|var)\s+(\w+)\s*[:=]", re.M)


def class_level_serializer_overrides(rel: str) -> list[tuple[str, str]]:
    """DTOs whose `@Serializable` names a custom serializer, which can emit any shape at all."""
    return [
        (m.group(2), m.group(1).strip())
        for m in CLASS_SERIALIZABLE.finditer(stripped(rel))
        if m.group(1).strip()
    ]


def body_properties(rel: str, name: str) -> list[str]:
    """Properties declared in the class body rather than the primary constructor.

    kotlinx.serialization emits an initialised body property with a backing field just like a
    constructor parameter, but this parser reads only the constructor — so such a field would be
    absent from the structural comparison, the annotation check and the fingerprint at once.
    """
    text = stripped(rel)
    m = re.search(r"\bdata class\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return []
    after = text[m.end() - 1 :]
    ctor_end = len(balanced(after, 0, "(", ")")) + 2
    rest = after[ctor_end:].lstrip()
    if not rest.startswith("{"):
        return []  # no class body at all
    return BODY_PROPERTY.findall(balanced(rest, 0, "{", "}"))


def annotated_properties(rel: str, name: str) -> list[tuple[str, str]]:
    """`(field, annotation)` for every annotation on a property of `name` that is not allowed."""
    text = stripped(rel)
    m = re.search(r"\bdata class\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return []
    found = []
    for param in split_params(balanced(text, m.end() - 1, "(", ")")):
        field = KT_FIELD.search(param)
        if not field:
            continue
        for annotation in KT_PROPERTY_ANNOTATION.findall(param):
            if annotation not in ALLOWED_DTO_ANNOTATIONS:
                found.append((field.group(1), annotation))
    return found


def kotlin_data_class(rel: str, name: str) -> dict[str, tuple[str, bool]]:
    """`{field: (type, has_default)}` for a Kotlin `data class` primary constructor."""
    text = stripped(rel)
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
    return {m.group(1): m.group(2) for m in KT_CONST.finditer(stripped(rel))}


# --------------------------------------------------------------------------
# TypeScript
# --------------------------------------------------------------------------

TS_FIELD = re.compile(r"(\w+)(\??)\s*:\s*([^;]+?);")


def ts_interface(rel: str, name: str) -> dict[str, tuple[str, bool]]:
    """`{field: (type, optional)}` for a TS interface. `optional` = declared `field?:`."""
    text = stripped(rel)
    m = re.search(r"\binterface\s+" + re.escape(name) + r"\s*\{", text)
    if not m:
        raise LookupError(f"interface {name} not found in {rel}")
    body = balanced(text, m.end() - 1, "{", "}")
    return {f.group(1): (f.group(3).strip(), f.group(2) == "?") for f in TS_FIELD.finditer(body)}


# `export` is optional. A module-local `const DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2` is a mirror
# like any other — it can compare a descriptor against its own stale copy without ever constructing
# a DTO, so discovery-by-use cannot see it either.
TS_CONST = re.compile(r"(?:export\s+)?const (\w+)\s*(?::\s*\w+\s*)?=\s*(.+?);", re.M)


def ts_consts(rel: str) -> dict[str, str]:
    return {m.group(1): m.group(2).strip() for m in TS_CONST.finditer(stripped(rel))}


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
        if rel.endswith(".ts") and _ts_root is None:
            continue  # extension not checked out; see `ts_root`
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

    # Discovery by use. Whatever a construction site stamps must be a registered constant *of that
    # file* — a global name set would accept any file declaring a same-named `val` (non-const, so
    # invisible to the name scan) and stamping it, which is a stale mirror wearing a registered
    # name. Aliases stay global: they name an indirection, not a value.
    # Resolution follows Kotlin's own rules rather than file identity: a stamp is legitimate when
    # the stamping file declares the registered constant, sits in the same package as a file that
    # does (no import needed), or imports it by name.
    by_package: dict[str, set[str]] = {}
    for s in sites:
        # TypeScript sites have no Kotlin package and, post-split, may not be on disk at all.
        if s["file"].endswith(".ts"):
            continue
        by_package.setdefault(s["symbol"], set()).add(package_declared_in(s["file"]))
    # Scoped, not global. Binding `schemaVersionSites` by package while leaving aliases as a bare
    # name set was an inconsistency in my own fix: any writer with a local `schemaVersion` property
    # would have been accepted purely because `DaemonBootstrapTask` registered that spelling.
    aliases = {
        (spec["file"], alias) for alias, spec in allowlist["versionStampAliases"].items()
    }
    for found_rel, expr in discover_version_stamps():
        if expr == POSITIONAL:
            failures.append(
                f"  {found_rel}: a descriptor is constructed without naming `schemaVersion`.\n"
                f"    A positional argument names nothing, so neither discovery-by-use nor the "
                f"name-based scan can see which version it stamps. Use the named argument."
            )
        elif expr.isdigit():
            failures.append(
                f"  {found_rel}: a descriptor is constructed with `schemaVersion = {expr}`, a bare "
                f"literal.\n    That is a mirror with no name, which no name-based scan can find. "
                f"Stamp a registered constant instead."
            )
        elif (found_rel, expr) not in aliases and not resolves_to_registered(
            found_rel, expr, by_package
        ):
            failures.append(
                f"  {found_rel}: a descriptor is constructed with `schemaVersion = {expr}`, which "
                f"does not resolve to a registered schema-version constant from here.\n"
                f"    Register the declaration in `schemaVersionSites`, or record the indirection "
                f"in `versionStampAliases`, in {ALLOWLIST.name}. Name matching alone would let a "
                f"stale local `val` of the same name pass in an unrelated package."
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
        keys = {m.group(1) for m in RAW_KEY.finditer(stripped(rel))}
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

        # Presence is not enough. `enabled` going from Boolean to String would keep its name, pass
        # every other rule, and still crash `.jsonPrimitive.boolean` at runtime — an untyped read
        # has no compiler to catch it, so the assumed type is recorded here instead.
        for key, expected in spec.get("assumedTypes", {}).items():
            if key not in keys:
                failures.append(
                    f"  {rel}: `assumedTypes` records `{key}`, but no `obj[\"{key}\"]` read "
                    f"remains. Prune it."
                )
            elif key in writer and writer[key][0] != expected:
                failures.append(
                    f"  {rel}: reads `{key}` assuming `{expected}`, but the writer now declares "
                    f"`{writer[key][0]}`.\n    The read is untyped, so this is a runtime crash "
                    f"rather than a compile error. Update the reader and this record together."
                )
        for key in sorted(keys & set(writer) - set(spec.get("assumedTypes", {}))):
            failures.append(
                f"  {rel}: reads `{key}` but records no assumed type for it in `assumedTypes`.\n"
                f"    An untyped read needs its expectation written down, or a retype slips past."
            )


NESTED_DTOS = ("BtaCompileConfig",)


def emitted_shape(writer: dict[str, tuple[str, bool]], allowlist: dict) -> dict:
    """The writer's fields plus the reader-only fields a registered writer actually emits.

    `jailCommand` and `hardTtlSeconds` are absent from the Gradle plugin's DTO but written by
    serve, so they are on the wire even though they are not in `DaemonClasspathDescriptor`.
    Fingerprinting the plugin's DTO alone meant renaming one of them moved the wire format while
    the digest held steady — the same hole the nested DTOs had, one field-set over.
    """
    reader = kotlin_data_class(JVM_READER, "DaemonLaunchDescriptor")
    shape = dict(writer)
    for field in allowlist["readerOnlyFields"].get("jvm", {}):
        if field in reader:
            shape[field] = reader[field]
    return shape


def wire_shape(writer: dict[str, tuple[str, bool]]) -> str:
    """The descriptor's on-the-wire shape, including the DTOs it nests.

    Hashing only the top level left `btaCompile` as the opaque token `BtaCompileConfig?`: renaming
    or retyping a field *inside* that class changed the wire contract for a released consumer while
    the digest held steady, and the field-for-field check passed too because both languages had been
    edited together. The nested shapes are part of the format, so they are part of its identity.
    """
    parts = [
        ";".join(
            f"{name}:{type_}{'?' if has_default else ''}"
            for name, (type_, has_default) in writer.items()
        )
    ]
    for dto in NESTED_DTOS:
        nested = kotlin_data_class(WRITER, dto)
        parts.append(
            dto
            + "{"
            + ";".join(
                f"{name}:{type_}{'?' if has_default else ''}"
                for name, (type_, has_default) in nested.items()
            )
            + "}"
        )
    return "|".join(parts)


def wire_fingerprint(writer: dict[str, tuple[str, bool]]) -> str:
    """A digest of the writer's on-the-wire shape: field names, types, and optionality.

    Version agreement between the copies is necessary and not sufficient. Nothing stopped a PR
    from renaming a field in the writer AND both readers in one commit, leaving every constant at
    v2 — in-repo everything agrees, while a released VS Code extension happily accepts the new
    descriptor as v2 and then misreads it. Pinning the shape makes the wire format's identity
    explicit: change it and this fails, which forces the version bump (or a deliberate decision
    that the change is backwards-compatible) to be part of the same diff.
    """
    return hashlib.sha256(wire_shape(writer).encode("utf-8")).hexdigest()[:16]


def check_wire_fingerprint(
    writer: dict[str, tuple[str, bool]], allowlist: dict, failures: list[str]
) -> None:
    actual = wire_fingerprint(emitted_shape(writer, allowlist))
    history = allowlist["wireFingerprint"]["history"]
    writer_version = kotlin_consts(WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]
    recorded = history.get(writer_version)

    if recorded is None:
        failures.append(
            f"  schema v{writer_version} has no recorded wire fingerprint. Add "
            f'`"{writer_version}": "{actual}"` to `wireFingerprint.history`, which is the point at '
            f"which the new shape gets looked at."
        )
    elif recorded != actual:
        # Deliberately NOT "update the digest". Making the digest editable in place meant a
        # breaking rename could pass by editing the writer, the readers and the record together,
        # leaving released v2 consumers accepting a v2 descriptor they now misread. A version's
        # shape is fixed once published; changing the shape means a new version.
        failures.append(
            f"  the descriptor's wire shape changed under schema v{writer_version}: "
            f"{recorded} -> {actual}.\n"
            f"    A version's shape is immutable once recorded, so this is not fixed by editing "
            f"the digest: bump `DAEMON_DESCRIPTOR_SCHEMA_VERSION` at every registered site and add "
            f"the new version to `history`, leaving the old entry in place. If you are certain the "
            f"change is invisible to every released reader, say so in the PR and amend the entry "
            f"deliberately — but that is a decision, not a refresh."
        )


# `*DESCRIPTOR_SCHEMA_VERSION` was the original pattern and it was too narrow: `ServeBundleDaemon`
# calls its copy `DAEMON_LAUNCH_SCHEMA_VERSION` and stamps real descriptors with it, so a fifth
# mirror sat outside a check whose whole claim was that every mirror is registered. Name matching
# is a heuristic either way — `discover_version_stamps` below is the one that cannot be renamed
# out of, and this stays to catch readers that only *compare* a version without constructing one.
VERSION_NAME = re.compile(
    r"(?:const val|(?:export\s+)?const) (\w*(?:DESCRIPTOR|DAEMON_LAUNCH)_SCHEMA_VERSION)\b"
)

# `src/test` and `src/functionalTest` were not enough: this repo also carries `commonTest`,
# `jvmTest`, `iosTest`, `desktopTest`, `screenshotTest` and `testFixtures` source sets, and a
# deliberately-skewed descriptor in any of them (a v1 payload proving the reader rejects it) is the
# point of the test rather than drift.
TEST_SOURCE_SET = re.compile(r"/src/[A-Za-z0-9]*([Tt]est|[Tt]estFixtures)[A-Za-z0-9]*/")


def is_test_source(rel: str) -> bool:
    return TEST_SOURCE_SET.search(f"/{rel}") is not None


# `(?<!class )` keeps the DTOs' own `data class …(` declarations out: they open a parameter list
# that looks exactly like a construction, and counting them made the checker report the two files
# that DEFINE the descriptor as constructing one without naming its version.
DESCRIPTOR_CTOR = re.compile(
    r"(?<!class )\b(?:DaemonLaunchDescriptor|DaemonClasspathDescriptor)\s*\("
)

# `descriptor.copy(schemaVersion = …)` re-stamps an existing instance without naming a class, so
# the constructor scan cannot see it. `schemaVersion` is a descriptor field name, so matching it on
# any `copy(` is precise enough in practice.
COPY_STAMP = re.compile(r"\.copy\s*\(\s*schemaVersion\s*=\s*([A-Za-z_]\w*|\d+)")
SCHEMA_ARG = re.compile(r"\bschemaVersion\s*=\s*([A-Za-z_]\w*|\d+)")

# Sentinel for a construction site that passes its arguments positionally.
POSITIONAL = "<positional>"


PACKAGE_LINE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def package_declared_in(rel: str) -> str:
    """The Kotlin package a file declares, or its directory for TypeScript."""
    m = PACKAGE_LINE.search(stripped(rel))
    return m.group(1) if m else rel.rsplit("/", 1)[0]


def resolves_to_registered(rel: str, symbol: str, by_package: dict[str, set[str]]) -> bool:
    """Would Kotlin resolve `symbol` here to one of the registered declarations?"""
    packages = by_package.get(symbol)
    if not packages:
        return False
    if package_declared_in(rel) in packages:
        return True
    text = stripped(rel)
    return any(
        re.search(r"^\s*import\s+" + re.escape(f"{pkg}.{symbol}") + r"\s*$", text, re.M)
        for pkg in packages
    )


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
        if is_test_source(rel):
            continue
        for m in DESCRIPTOR_CTOR.finditer(text):
            try:
                body = balanced(text, m.end() - 1, "(", ")")
            except IndexError:
                continue  # a construction split across an unbalanced fragment; nothing to read
            found = [arg.group(1) for arg in SCHEMA_ARG.finditer(body)]
            if found:
                stamps.extend((rel, expr) for expr in found)
            else:
                # A positional `DaemonLaunchDescriptor(1, …)` names nothing, so neither this scan
                # nor the name-based one can see the version it stamps. Reported as an unnamed
                # stamp rather than silently skipped.
                stamps.append((rel, POSITIONAL))
        for m in COPY_STAMP.finditer(text):
            stamps.append((rel, m.group(1)))
    return stamps


PRUNE = {"build", "node_modules", ".git", ".gradle", "out", "dist", "scripts"}


def walk_sources() -> list[tuple[str, str]]:
    """`(relative path, comment-stripped text)` for every Kotlin/TypeScript source in the repo.

    Prunes whole directories rather than filtering paths after the walk: `node_modules` and the
    per-module `build/` trees hold far more sources than the repo does, and descending into them
    to discard the results costs more than every other check here put together.
    """
    out: list[tuple[str, str]] = []
    # Both roots, so the "unregistered mirror" arm still sees TypeScript. The extension's sources
    # left this repo with it; scanning only REPO_ROOT would let a new TS mirror appear unregistered
    # and keep reporting green — the exact blindness this discovery exists to prevent. Paths from
    # the extension are relative to ITS root, which is how the allowlist spells them.
    # Every root that can hold a copy of this schema. `daemon/protocol` used to be under
    # REPO_ROOT, so its sources were walked for free; after the cutover they are in the contracts
    # repository and a new production file there could declare an unregistered mirror, or copy a
    # descriptor with a stale literal, without this gate seeing it — the same blindness the
    # extension's TypeScript would have had. Paths from an external root are relative to THAT
    # root, which is how the allowlist spells them.
    roots = (
        [REPO_ROOT]
        + ([_ts_root] if _ts_root is not None else [])
        + ([_contracts_root] if _contracts_root is not None else [])
    )
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(
                d for d in dirnames if d not in PRUNE and not d.startswith(".")
            )
            for filename in sorted(filenames):
                if not filename.endswith((".kt", ".ts")):
                    continue
                path = Path(dirpath) / filename
                out.append(
                    (
                        path.relative_to(root).as_posix(),
                        strip_comments(
                            path.read_text(encoding="utf-8"), nested=not filename.endswith(".ts")
                        ),
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

    # A reader-only entry claims "the writer never emits this". Once the writer does, the claim is
    # false and the entry has to go — otherwise it sits there as a standing exemption that would
    # silently authorise the field's removal later. A debt register that stops describing reality
    # is the failure this whole check exists to prevent.
    for field in sorted(set(reader_only) & set(writer)):
        failures.append(
            f"  {rel}: `{field}` is recorded in `readerOnlyFields` as something the writer never "
            f"emits, but the writer now declares it.\n    Remove the entry — it is a stale "
            f"exemption, and leaving it would license a later removal without review."
        )

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
            # A writer-only entry claims this reader does not declare the field. Once it does, the
            # exemption is stale and would later authorise a removal unreviewed. The unit test
            # asserted this; `check()` did not — and `:cli:checkDaemonLaunchSchema` runs `check()`,
            # so the gate people actually run was the one missing it.
            tolerated = allowlist["writerOnlyFields"].get(field)
            if tolerated and label in tolerated["invisibleTo"]:
                failures.append(
                    f"  {rel}: `{field}` is recorded in `writerOnlyFields` as invisible to the "
                    f"{label} reader, but that reader now declares it.\n    Remove `{label}` from "
                    f"its `invisibleTo` — a stale exemption licenses a later removal unreviewed."
                )
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
    if not JVM_READER:
        return
    text = stripped(JVM_READER)
    # Find the receiver `parse` decodes through, then prove THAT instance is the tolerant one.
    # Matching any `Json { ignoreUnknownKeys = true }` in the file proved only that a tolerant
    # parser exists somewhere in it — a refactor to the default `Json`, or a second unrelated
    # tolerant instance, would keep the check green while every plugin-written descriptor (which
    # carries the writer-only `btaCompile`) failed to parse.
    call = re.search(r"fun parse\([^)]*\)[^=]*=\s*(\w+)\s*\.decodeFromString", text, re.S)
    receiver = call.group(1) if call else None
    tolerant = receiver is not None and re.search(
        r"\b(?:private\s+)?val\s+" + re.escape(receiver) + r"\s*=\s*Json\s*\{[^}]*"
        r"ignoreUnknownKeys\s*=\s*true",
        text,
        re.S,
    )
    if receiver is None:
        failures.append(
            f"  {JVM_READER}: could not find the `Json` instance `parse` decodes through, so "
            f"unknown-key tolerance is unverifiable. Update the matcher rather than dropping the "
            f"check — the writer emits "
            + ", ".join(f"`{f}`" for f in sorted(allowlist["writerOnlyFields"]))
            + " which this reader does not declare."
        )
    elif not tolerant:
        failures.append(
            f"  {JVM_READER}: `parse` decodes through `{receiver}`, which is not a "
            f"`Json { { } }` configured with `ignoreUnknownKeys = true`.\n"
            f"    The writer emits "
            + ", ".join(f"`{f}`" for f in sorted(allowlist["writerOnlyFields"]))
            + " which this reader does not declare; without that setting every plugin-written "
            "descriptor fails to parse."
        )


JSON_CONFIG = re.compile(r"\bJson\s*\{([^}]*)\}", re.S)
JSON_SETTING = re.compile(r"(\w+)\s*=\s*([^\s;]+)")
ENCODE_CALL = re.compile(r"\b(\w+)\s*\.\s*encodeToString\s*\(")


def check_writer_encoders(allowlist: dict, failures: list[str]) -> None:
    """The `Json` configuration each writer encodes through is part of the wire format.

    The fingerprint starts from the DTO declaration, which says nothing about how it is serialised.
    A `namingStrategy` on either writer would rename every key at once while the declaration, the
    digest, both readers and every version constant stayed identical. And there are two encoder
    configs, hand-maintained separately — the same duplication this whole check exists for, one
    level further out, so they are also compared against each other by construction.
    """
    for rel, required in allowlist["writerEncoders"]["declaredBy"].items():
        text = stripped(rel)
        # Resolve the receiver the descriptor is actually encoded through, the same way the
        # reader-side tolerance check resolves `parse`'s. Validating every syntactic `Json { … }`
        # in the file was the weaker shape: switching `encodeToString` to a different encoder while
        # leaving the compliant instance in place for something else would have kept this green.
        call = ENCODE_CALL.search(text)
        if not call:
            failures.append(
                f"  {rel}: registered as serialising a descriptor, but no "
                f"`<receiver>.encodeToString(…)` call was found. Prune the entry or fix the "
                f"matcher rather than leaving the encoder unverified."
            )
            continue
        receiver = call.group(1)
        declared = re.search(
            r"\bval\s+" + re.escape(receiver) + r"\s*(?::\s*Json\s*)?=\s*Json\s*\{([^}]*)\}",
            text,
            re.S,
        )
        if not declared:
            failures.append(
                f"  {rel}: encodes the descriptor through `{receiver}`, which is not a "
                f"locally-declared `Json {{ … }}`.\n    The encoder's configuration is part of "
                f"the wire format, so it has to be visible here to be checked."
            )
            continue
        settings = dict(JSON_SETTING.findall(declared.group(1)))
        if settings != required:
            failures.append(
                f"  {rel}: `{receiver}`, the encoder the descriptor is serialised through, is "
                f"configured {settings}, but the recorded wire contract is {required}.\n"
                f"    Encoder settings rename or drop keys without touching a single declaration "
                f"— a `namingStrategy` alone would move every key while the fingerprint held "
                f"steady. Both writers must agree, and with this record."
            )


def check_mirrored_constants(allowlist: dict, failures: list[str]) -> None:
    """String constants the descriptor carries that other modules re-declare."""
    for name, spec in allowlist["mirroredConstants"].items():
        if not available(spec["declaredBy"]):
            continue
        source = kotlin_consts(spec["declaredBy"]).get(name)
        if source is None:
            failures.append(
                f"  {spec['declaredBy']}: `{name}` is registered as the source of a mirrored "
                f"constant but is not declared there."
            )
            continue
        # A key is not only mirrored by Kotlin constants. The production image passes
        # `-Dcomposeai.daemon.sandboxCount=3` as a literal in its `JAVA_TOOL_OPTIONS`, so renaming
        # the key consistently across every Kotlin copy would still leave deployed hosts setting a
        # property nothing reads — silently falling back to a pool of one.
        for rel in spec.get("alsoAppearsIn", []):
            if not available(rel):
                continue
            if source.strip('"') not in read(rel):
                failures.append(
                    f"  {rel}: does not contain {source}, which it is registered as carrying for "
                    f"`{name}`.\n    {spec['why']}"
                )

        for rel in spec["mirroredBy"]:
            if not available(rel):
                continue
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
    if JVM_READER:
        check_reader(
            "jvm",
            JVM_READER,
            kotlin_data_class(JVM_READER, "DaemonLaunchDescriptor"),
            writer,
            allowlist,
            failures,
            ts=False,
        )
    if TS_READER:
        check_reader(
            "vscode",
            TS_READER,
            ts_interface(TS_READER, "DaemonLaunchDescriptor"),
            writer,
            allowlist,
            failures,
            ts=True,
        )
    else:
        print(
            "check-daemon-launch-schema: SKIPPING the TypeScript reader — no "
            "compose-preview-vscode checkout (set COMPOSE_PREVIEW_VSCODE_ROOT). "
            "The Kotlin reader is still checked.",
            file=sys.stderr,
        )
    check_unknown_key_tolerance(allowlist, failures)
    check_mirrored_constants(allowlist, failures)
    check_raw_key_readers(writer, allowlist, failures)
    check_writer_encoders(allowlist, failures)
    # The emitted shape folds in the JVM reader's reader-only fields, so without that checkout the
    # digest would be computed from a shape the wire never has — reporting a shape change that did
    # not happen. Skipping is the honest answer; CI checks the contracts repository out, so the
    # fingerprint is still enforced where it counts.
    if JVM_READER:
        check_wire_fingerprint(writer, allowlist, failures)

    # A custom serializer replaces the generated one entirely and can emit any keys and types it
    # likes, with every parsed field and the fingerprint unmoved.
    for dto, argument in class_level_serializer_overrides(WRITER):
        failures.append(
            f"  {WRITER}: `{dto}` is annotated `@Serializable({argument})`.\n    A custom "
            f"serializer decides the wire format itself, so nothing this checker reads from the "
            f"declaration describes what is emitted any more. Drop it, or model it here — do not "
            f"leave it unexamined."
        )

    # Annotations on these properties change what is emitted without changing the declaration that
    # every rule here reads. Refused as a class rather than one at a time.
    for dto in ("DaemonClasspathDescriptor",) + NESTED_DTOS:
        for rename in serial_name_renames(WRITER, dto):
            failures.append(
                f"  {WRITER}: `{dto}.{rename}` uses `@SerialName`, so the JSON key no longer "
                f"matches the Kotlin identifier.\n    Every rule here compares identifiers, so a "
                f"wire rename this way would be invisible to all of them. Either drop the "
                f"annotation, or teach this checker to read it — do not leave it unhandled."
            )
        for field in body_properties(WRITER, dto):
            failures.append(
                f"  {WRITER}: `{dto}.{field}` is declared in the class body, not the primary "
                f"constructor.\n    kotlinx.serialization emits an initialised body property like "
                f"any other field, but every rule here reads the constructor — so it would be "
                f"invisible to the structural comparison, the annotation check and the fingerprint "
                f"at once. Move it into the constructor, or make it non-serialised."
            )
        for field, annotation in annotated_properties(WRITER, dto):
            if annotation == "SerialName":
                continue  # reported above, with a better message
            failures.append(
                f"  {WRITER}: `{dto}.{field}` carries `@{annotation}`, which is not in "
                f"`ALLOWED_DTO_ANNOTATIONS`.\n    An annotation can change what "
                f"kotlinx.serialization emits (`@Transient` drops the field, `@EncodeDefault` "
                f"changes whether a default is written) while the declaration this checker reads "
                f"stays identical. Decide what it means for the wire format and record it, rather "
                f"than letting it through unexamined."
            )

    # `BtaCompileConfig` claims to be field-for-field across the two languages. Only checkable
    # with the extension checked out; see `ts_root`. Guarded with `if`, never an early `return` —
    # the exit-code handling below has to run either way.
    if TS_READER:
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
    # Name the readers actually compared, and say which were skipped. A fixed "2 readers agree"
    # was fine while both lived here; now either can be absent, and a summary that claims two
    # while checking one is the failure this gate exists to prevent, printed in its own output.
    checked = [name for name, rel in (("jvm", JVM_READER), ("vscode", TS_READER)) if rel]
    skipped = [name for name, rel in (("jvm", JVM_READER), ("vscode", TS_READER)) if not rel]
    readers = f"{len(checked)} reader(s) agree ({', '.join(checked) or 'none'})"
    if skipped:
        readers += f", {len(skipped)} SKIPPED ({', '.join(skipped)}) — no checkout"
    print(
        f"check-daemon-launch-schema: OK — {len(writer)} writer field(s), {readers}, "
        f"{sites} schema-version site(s) at v"
        + kotlin_consts(WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]
    )
    return 0


if __name__ == "__main__":
    sys.exit(check())
