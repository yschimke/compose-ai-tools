#!/usr/bin/env python3
"""Keep this repository's `daemon-launch.json` representations from drifting apart.

`<module>/build/compose-previews/daemon-launch.json` is written by the Gradle
plugin and read by the daemon JVM and the CLI doctor. Since contracts 3.28.0 the
shape is declared once, by the contracts' `DaemonLaunchDescriptor` (plus its
nested `BtaCompileConfig`), which the plugin's `daemon-launch-builder` writes
through and every JVM reader parses. What is still duplicated is the schema
version, the encoder configuration and the raw-key reads, and two of the version
copies carry a comment asking a human to remember:

    render-session/subprocess/.../SubprocessRenderSession.kt
        /** ... mirrors the gradle plugin's writer. */
        private const val DAEMON_DESCRIPTOR_SCHEMA_VERSION = 2

    cli/.../McpCommand.kt
        // Pinned to the writer's schema version. Keep in sync — bump together.
        internal const val EXPECTED_DESCRIPTOR_SCHEMA_VERSION: Int = 2

A comment is not a gate. Bumping the writer alone leaves those two writing and
expecting v2 with nothing failing.

This matters for the preview-server split (#3824) beyond ordinary tidiness:
`:render-session-subprocess` is one of the twelve published contract modules an
extracted server links against. Once that is a repo boundary, a bump on one
side and a stale mirror on the other stops being a same-commit mistake and
becomes a cross-repo version skew no compiler sees.

What this checks
----------------

1. **Version agreement** — every declaration of the descriptor schema version
   equals the writer's `DaemonLaunchDescriptor.SCHEMA_VERSION`, and every site is
   registered. An unregistered copy fails: a new mirror must be declared here,
   which is the point at which someone can ask whether it should exist at all.

2. **Structural agreement** — for the JVM reader (`DaemonLaunchDescriptor`):
   every field the reader *requires* is emitted by the writer, shared fields
   carry corresponding types, and any reader-only field is optional. Since the
   writer IS that type this holds by construction; the rule stays so that a
   writer moving off it again is compared rather than trusted.

3. **Unknown-key tolerance** — while the writer emits a field the JVM reader
   does not declare (`writerOnlyFields`), that reader must keep
   `ignoreUnknownKeys = true`. Nothing is writer-only today.

4. **Sysprop key mirrors** — string constants the descriptor carries that are
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

# The JVM reader moved to yschimke/compose-preview-contracts with the wire contracts. It remains
# part of this check because this repository resolves that exact published contracts release.
JVM_READER_REL = (
    "daemon/protocol/src/main/kotlin/ee/schimke/composeai/daemon/protocol/DaemonLaunchDescriptor.kt"
)
# Since contracts 3.28.0 the writer is the same declaration: `daemon-launch-builder` constructs and
# encodes the contracts' `DaemonLaunchDescriptor` rather than declaring its own
# `DaemonClasspathDescriptor`. The shape therefore lives in the contracts checkout, and so does the
# version constant the writer stamps.
WRITER_REL = JVM_READER_REL
WRITER_DTO = "DaemonLaunchDescriptor"
WRITER_VERSION_SYMBOL = "SCHEMA_VERSION"
# The DTOs the descriptor nests, and the contracts file each is declared in.
NESTED_DTO_FILES = {
    "BtaCompileConfig": (
        "daemon/protocol/src/main/kotlin/ee/schimke/composeai/daemon/protocol/BtaCompileConfig.kt"
    ),
}
NESTED_DTOS = tuple(NESTED_DTO_FILES)
# Paths under the contracts' `:daemon-protocol` module resolve against the contracts checkout.
CONTRACTS_PREFIX = "daemon/protocol/"
# The plugin's former DTO, now a deprecated facade kept for source and binary consumers until a
# major release (`docs/VERSIONING.md` § 5). Nothing writes through it: its conversions copy an
# existing descriptor's version rather than stamping one, so it is a declaring file below, not a
# construction site.
LEGACY_FACADE = (
    "gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/daemonlaunch/"
    "DaemonClasspathDescriptor.kt"
)


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


_contracts_root = contracts_root()
JVM_READER = JVM_READER_REL if _contracts_root is not None else ""
WRITER = WRITER_REL if _contracts_root is not None else ""


def dto_file(dto: str) -> str:
    """The file declaring `dto`: the writer itself, or one of the DTOs it nests."""
    return WRITER_REL if dto == WRITER_DTO else NESTED_DTO_FILES[dto]


def resolve(rel: str) -> Path | None:
    """Map a path from this script or the allowlist onto disk.

    The JVM reader (and, since contracts 3.28.0, the writer and its nested DTOs) moved to
    compose-preview-contracts with the wire contracts. They resolve against the pinned checkout when
    present and is unavailable otherwise, so a missing sibling SKIPS that
    half rather than failing: "the other repository is not checked out" is not a drift signal, and
    this script is wired into `check`, which every contributor runs.
    """
    if rel.startswith(CONTRACTS_PREFIX):
        return (_contracts_root / rel) if _contracts_root is not None else None
    return REPO_ROOT / rel


def available(rel: str) -> bool:
    """Whether `rel` is present in this checkout.

    False only for a path under the wire contracts whose compose-preview-contracts checkout is
    absent. Callers that walk allowlist-supplied paths use this to skip rather than crash, for the
    same reason the reader checks are guarded: "the other repository is not checked out" is not a
    drift signal, and this script runs inside `check`, which every contributor runs.

    CI is where the cross-repo half must be real, and it checks both repositories out; the
    schema-gate step there fails if any test reports a skip.
    """
    return resolve(rel) is not None


def read(rel: str) -> str:
    path = resolve(rel)
    if path is None:
        raise FileNotFoundError(
            f"{rel} lives in another repository; set COMPOSE_PREVIEW_CONTRACTS_ROOT"
        )
    return path.read_text(encoding="utf-8")


def stripped(rel: str) -> str:
    """`read` with comments removed."""
    return strip_comments(read(rel))


def strip_comments(text: str) -> str:
    """Remove comments, keeping string literals intact.

    String-aware on purpose. A stripper that only looks for `//` and `/*` truncates
    `"https://host"` at the slashes, and a literal containing `/*` opens a block comment that
    swallows the rest of the file — which for a repo-wide scanner means a declaration can hide
    behind an ordinary URL. It keeps the contents because mirrored constants ARE strings
    (`"composeai.daemon.sandboxCount"`), so blanking them would defeat the comparison.

    Handles Kotlin raw strings and char literals. Kotlin block comments nest, so the scanner tracks
    their depth rather than stopping at the first closer.
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


def data_class_header(text: str, name: str) -> re.Match | None:
    """The `data class <name> … (` match whose end sits on the primary constructor's `(`.

    The modifier clause between the name and the parameter list is not optional decoration: the
    contracts 3.0.0 cutover put these DTOs behind builders, so the reader now declares

        public data class DaemonLaunchDescriptor
        internal constructor(

    — `@ConsistentCopyVisibility` plus an internal constructor is how that repository keeps
    `<init>` and `copy$default` off its published ABI, because adding a property to a data class
    removes both signatures and kills a consumer compiled against the previous release with
    `NoSuchMethodError`. This used to require the parameter list immediately after the name, and
    stopped matching the moment the modifier appeared — which surfaced as a `LookupError` from the
    parsers rather than as a drift report.
    """
    return re.search(
        r"\bdata class\s+"
        + re.escape(name)
        + r"\s*(?:(?:public|internal|private|protected)\s+)?(?:constructor\s*)?\(",
        text,
    )


def serial_name_renames(rel: str, name: str) -> list[str]:
    """Fields of `name` carrying `@SerialName`, whose wire key differs from their identifier."""
    text = stripped(rel)
    m = data_class_header(text, name)
    if not m:
        return []
    found = []
    for param in split_params(balanced(text, m.end() - 1, "(", ")")):
        rename = KT_SERIAL_NAME.search(param)
        field = KT_FIELD.search(param)
        if rename and field and rename.group(1) != field.group(1):
            found.append(f"{field.group(1)} -> \"{rename.group(1)}\"")
    return found


# Other annotations may sit between `@Serializable(...)` and the class, as `@ConsistentCopyVisibility`
# does on every builder-shaped contracts DTO.
CLASS_SERIALIZABLE = re.compile(
    r"@Serializable\s*\(([^)]*)\)\s*(?:@\w+\s*)*(?:public\s+)?data class\s+(\w+)"
)
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
    m = data_class_header(text, name)
    if not m:
        return []
    after = text[m.end() - 1 :]
    ctor_end = len(balanced(after, 0, "(", ")")) + 2
    rest = after[ctor_end:].lstrip()
    if not rest.startswith("{"):
        return []  # no class body at all
    return BODY_PROPERTY.findall(top_level(balanced(rest, 0, "{", "}")))


def top_level(body: str) -> str:
    """`body` with everything nested in braces or parentheses blanked out.

    The contracts DTOs have class bodies: a `Builder` whose `var`s mirror every field, and a
    companion holding the parser's `Json`. Neither is a serialised property of the DTO, and
    matching the whole body reported each as one. Only what sits directly in the class body is.
    """
    out, depth = [], 0
    for c in body:
        if c in "{(":
            depth += 1
        elif c in "})":
            depth -= 1
        elif depth == 0:
            out.append(c)
            continue
        if c == "\n":
            out.append(c)
    return "".join(out)


def annotated_properties(rel: str, name: str) -> list[tuple[str, str]]:
    """`(field, annotation)` for every annotation on a property of `name` that is not allowed."""
    text = stripped(rel)
    m = data_class_header(text, name)
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
    m = data_class_header(text, name)
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


# A `const val` whose value is another constant rather than a literal: `DaemonProperties.Names.
# SANDBOX_COUNT`. Anchored on the whole value so a string containing a dotted word is not mistaken
# for one.
KT_CONST_REF = re.compile(r"^([A-Z]\w*(?:\.\w+)*)\.(\w+)$")


def resolve_const(value: str, registries: dict[str, str]) -> tuple[str | None, str | None]:
    """Resolve a `const val`'s right-hand side to the literal it ultimately holds.

    A mirror that *reads* the shared constant instead of re-typing the string cannot disagree with
    it, which is strictly better than a mirror — but the value this scanner reads is then the
    reference text, not the string, and comparing `DaemonProperties.Names.SANDBOX_COUNT` against
    `"composeai.daemon.sandboxCount"` fails on two spellings of the same key. That is what
    #5182 hit: the typed sysprop registry landed and this gate went red on main for a change that
    made the mirrors *safer*.

    So a dotted reference is followed to the file `constantRegistries` names for its qualifier, and
    the literal there is what gets compared. An unregistered qualifier is still a failure — the gate
    must never pass on a value it could not read — and it says which line to add.

    Returns `(literal, None)` or `(None, why-it-could-not-be-resolved)`.
    """
    ref = KT_CONST_REF.match(value)
    if ref is None:
        return value, None

    qualifier, symbol = ref.group(1), ref.group(2)
    rel = registries.get(qualifier)
    if rel is None:
        return None, (
            f"reads `{value}`, and `{qualifier}` is not a registered constant registry.\n"
            f"    Add `\"{qualifier}\": \"<path to the file declaring it>\"` to "
            f"`constantRegistries` in {ALLOWLIST.name} so the value behind the reference is still "
            f"compared. Reading the shared constant is the right move; the gate just has to be able "
            f"to follow it."
        )
    if not available(rel):
        return None, None

    declared = kotlin_consts(rel).get(symbol)
    if declared is None:
        return None, (
            f"reads `{value}`, but {rel} declares no `{symbol}`.\n"
            f"    Either the constant was renamed, or `constantRegistries` points at the wrong file."
        )
    # One hop only: a registry entry pointing at another reference is a chain nobody needs, and
    # refusing it keeps this resolver from having to guard against a cycle.
    return declared, None


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def writer_version() -> str | None:
    """The schema version the writer stamps, or None without a contracts checkout."""
    return kotlin_consts(WRITER)[WRITER_VERSION_SYMBOL] if WRITER else None


def check_versions(allowlist: dict, failures: list[str]) -> None:
    """Every declared copy of the schema version agrees, and every copy is registered."""
    sites = allowlist["schemaVersionSites"]
    registries = allowlist.get("constantRegistries", {})
    written = writer_version()

    for site in sites:
        rel, symbol = site["file"], site["symbol"]
        if not available(rel):
            continue  # the writer's own constant, without a contracts checkout
        consts = kotlin_consts(rel)
        if symbol not in consts:
            failures.append(
                f"  {rel}: `{symbol}` is registered as a schema-version mirror but no longer "
                f"exists.\n    Remove it from `schemaVersionSites` if the mirror is genuinely "
                f"gone — that is progress, not a failure to hide."
            )
            continue
        # A site that forwards the writer's constant (`= DaemonLaunchDescriptor.SCHEMA_VERSION`)
        # rather than re-typing the number is followed to the literal behind it, as mirrored
        # constants are.
        value, why = resolve_const(consts[symbol], registries)
        if value is None:
            if why is not None:
                failures.append(f"  {rel}: `{symbol}` {why}")
            continue
        if written is not None and value != written:
            failures.append(
                f"  {rel}: `{symbol}` = {value}, but the writer "
                f"({WRITER.split('/')[-1]}) says {written}.\n"
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
        if available(s["file"]):
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
        elif "." in expr:
            why = qualified_stamp_problem(found_rel, expr, sites, registries)
            if why is not None:
                failures.append(f"  {found_rel}: `schemaVersion = {expr}` {why}")
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


def qualified_stamp_problem(
    rel: str, expr: str, sites: list[dict], registries: dict[str, str]
) -> str | None:
    """Why a qualified stamp such as `DaemonLaunchDescriptor.SCHEMA_VERSION` is not legitimate.

    The writer stamps the contracts' own constant through its class, so the stamp is a dotted
    reference rather than a bare name. It is legitimate when the qualifier is a registered constant
    registry, the symbol is a registered schema-version site *in that registry's file*, and Kotlin
    would resolve the qualifier here to that declaration (same package, or imported by name).
    Returns None when legitimate, or when the registry lives in an absent contracts checkout.
    """
    qualifier, symbol = expr.rsplit(".", 1)
    target = registries.get(qualifier)
    if target is None:
        return (
            f"stamps through `{qualifier}`, which is not a registered constant registry.\n"
            f"    Add `\"{qualifier}\": \"<path to the file declaring it>\"` to `constantRegistries` "
            f"in {ALLOWLIST.name}, and register the constant in `schemaVersionSites`."
        )
    if (target, symbol) not in {(s["file"], s["symbol"]) for s in sites}:
        return (
            f"reads `{symbol}` from {target}, which is not a registered schema-version site.\n"
            f"    Register it in `schemaVersionSites` in {ALLOWLIST.name}."
        )
    if not available(target):
        return None
    pkg = package_declared_in(target)
    simple = qualifier.split(".")[0]
    if package_declared_in(rel) == pkg:
        return None
    if re.search(
        r"^\s*import\s+" + re.escape(f"{pkg}.{simple}") + r"\s*$", stripped(rel), re.M
    ):
        return None
    return (
        f"names `{simple}`, but this file neither imports `{pkg}.{simple}` nor sits in `{pkg}`, "
        f"so it is not the registered declaration."
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


def emitted_shape(writer: dict[str, tuple[str, bool]], allowlist: dict) -> dict:
    """The writer's fields plus the reader-only fields a registered writer actually emits.

    `jailCommand` and `hardTtlSeconds` were absent from the Gradle plugin's former DTO
    (`DaemonClasspathDescriptor`) but written by serve, so they were on the wire without being in
    the writer. Fingerprinting the plugin's DTO alone meant renaming one of them moved the wire
    format while the digest held steady — the same hole the nested DTOs had, one field-set over.
    Since the writer became the contracts type it declares them itself and `readerOnlyFields` is
    empty; the fold stays for the next reader-only field.
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
        nested = kotlin_data_class(dto_file(dto), dto)
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
    writer_version = kotlin_consts(WRITER)[WRITER_VERSION_SYMBOL]
    recorded = history.get(writer_version)

    # An amended entry must say so. `amendments` is the audit trail for the rare deliberate edit
    # (a change invisible to every released reader); a digest that moved without one is a refresh.
    last_amended: dict[str, str] = {}
    for amendment in allowlist["wireFingerprint"].get("amendments", []):
        if not amendment.get("why", "").strip():
            failures.append(
                f"  wireFingerprint.amendments: the v{amendment.get('version')} amendment "
                f"{amendment.get('from')} -> {amendment.get('to')} records no `why`."
            )
        last_amended[amendment["version"]] = amendment["to"]
    for version, to in last_amended.items():
        if history.get(version) != to:
            failures.append(
                f"  wireFingerprint.amendments: the last v{version} amendment ends at {to}, but "
                f"`history` records {history.get(version)}. An entry changes only through an "
                f"amendment that says why."
            )

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
            f"the digest: bump `DaemonLaunchDescriptor.SCHEMA_VERSION` and every registered site and "
            f"add the new version to `history`, leaving the old entry in place. If you are certain "
            f"the change is invisible to every released reader, say so in the PR and amend the "
            f"entry deliberately, recording it in `amendments` — a decision, not a refresh."
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
#
# `DaemonLaunchDescriptor.Builder(` is a construction too, and since contracts 3.0.0 the only public
# one: the constructor is internal. Matching the class name alone saw none of them, so the gate's
# discovery-by-use was blind to every writer that moved to the builder — the plugin's included.
DESCRIPTOR_CTOR = re.compile(
    r"(?<!class )\b(?:DaemonLaunchDescriptor(?:\s*\.\s*Builder)?|DaemonClasspathDescriptor)\s*\("
)

# `descriptor.copy(schemaVersion = …)` re-stamps an existing instance without naming a class, so
# the constructor scan cannot see it. `schemaVersion` is a descriptor field name, so matching it on
# any `copy(` is precise enough in practice.
# The stamped expression may be qualified (`DaemonLaunchDescriptor.SCHEMA_VERSION`): capturing only
# the first identifier read that as a stamp of `DaemonLaunchDescriptor`.
COPY_STAMP = re.compile(r"\.copy\s*\(\s*schemaVersion\s*=\s*([A-Za-z_][\w.]*\w|[A-Za-z_]|\d+)")
SCHEMA_ARG = re.compile(r"\bschemaVersion\s*=\s*([A-Za-z_][\w.]*\w|[A-Za-z_]|\d+)")

# Sentinel for a construction site that passes its arguments positionally.
POSITIONAL = "<positional>"


PACKAGE_LINE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def package_declared_in(rel: str) -> str:
    """The Kotlin package a file declares."""
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
        if is_test_source(rel) or rel in DECLARING:
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


# The two files that DECLARE a descriptor. Their own construction of one is the type's
# construction API, not a site that stamps a version: the contracts 3.0.0 builder passes the
# caller's `schemaVersion` through positionally in `Builder.build()`, inside the very file the
# reader is parsed from. `(?<!class )` used to keep the declarations out on its own, and stopped
# doing so the moment a declaration grew a builder that constructs the type by name.
DECLARING = {WRITER_REL, JVM_READER_REL, LEGACY_FACADE, *NESTED_DTO_FILES.values()}


PRUNE = {"build", "node_modules", ".git", ".gradle", "out", "dist", "scripts"}


def walk_sources() -> list[tuple[str, str]]:
    """`(relative path, comment-stripped text)` for every Kotlin source in the relevant trees.

    Prunes whole directories rather than filtering paths after the walk: `node_modules` and the
    per-module `build/` trees hold far more sources than the repo does, and descending into them
    to discard the results costs more than every other check here put together.
    """
    out: list[tuple[str, str]] = []
    # Every root that can hold a Kotlin copy of this schema. `daemon/protocol` used to be under
    # REPO_ROOT, so its sources were walked for free; after the cutover they are in the contracts
    # repository and a new production file there could declare an unregistered mirror, or copy a
    # descriptor with a stale literal, without this gate seeing it. Paths from the external root
    # are relative to that root, which is how the allowlist spells them.
    roots = [REPO_ROOT] + ([_contracts_root] if _contracts_root is not None else [])
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(
                d for d in dirnames if d not in PRUNE and not d.startswith(".")
            )
            for filename in sorted(filenames):
                if not filename.endswith(".kt"):
                    continue
                path = Path(dirpath) / filename
                out.append(
                    (
                        path.relative_to(root).as_posix(),
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
        if wtype != rtype:
            failures.append(
                f"  {rel}: `{field}` is `{rtype}` in the {label} reader but `{wtype}` in the "
                f"writer (expected `{wtype}`)."
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
    registries = allowlist.get("constantRegistries", {})

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
        source, why = resolve_const(source, registries)
        if source is None:
            if why is not None:
                failures.append(f"  {spec['declaredBy']}: `{name}` {why}")
            continue
        for rel in spec["mirroredBy"]:
            if not available(rel):
                continue
            symbol = spec.get("mirrorSymbol", name)
            value = kotlin_consts(rel).get(symbol)
            if value is None:
                failures.append(
                    f"  {rel}: registered as mirroring `{name}` but no longer declares it. "
                    f"Prune it from `mirroredConstants`."
                )
                continue
            value, why = resolve_const(value, registries)
            if value is None:
                if why is not None:
                    failures.append(f"  {rel}: `{symbol}` {why}")
                continue
            if value != source:
                failures.append(
                    f"  {rel}: `{name}` = {value}, but {spec['declaredBy']} says {source}. "
                    f"{spec['why']}"
                )


def check_dto_declarations(failures: list[str]) -> None:
    """Refuse what would make the declaration stop describing what is emitted."""
    for dto in (WRITER_DTO,) + NESTED_DTOS:
        rel = dto_file(dto)
        # A custom serializer replaces the generated one entirely and can emit any keys and types
        # it likes, with every parsed field and the fingerprint unmoved.
        for name, argument in class_level_serializer_overrides(rel):
            if name != dto:
                continue
            failures.append(
                f"  {rel}: `{dto}` is annotated `@Serializable({argument})`.\n    A custom "
                f"serializer decides the wire format itself, so nothing this checker reads from the "
                f"declaration describes what is emitted any more. Drop it, or model it here — do "
                f"not leave it unexamined."
            )
        # Annotations on these properties change what is emitted without changing the declaration
        # that every rule here reads. Refused as a class rather than one at a time.
        for rename in serial_name_renames(rel, dto):
            failures.append(
                f"  {rel}: `{dto}.{rename}` uses `@SerialName`, so the JSON key no longer "
                f"matches the Kotlin identifier.\n    Every rule here compares identifiers, so a "
                f"wire rename this way would be invisible to all of them. Either drop the "
                f"annotation, or teach this checker to read it — do not leave it unhandled."
            )
        for field in body_properties(rel, dto):
            failures.append(
                f"  {rel}: `{dto}.{field}` is declared in the class body, not the primary "
                f"constructor.\n    kotlinx.serialization emits an initialised body property like "
                f"any other field, but every rule here reads the constructor — so it would be "
                f"invisible to the structural comparison, the annotation check and the fingerprint "
                f"at once. Move it into the constructor, or make it non-serialised."
            )
        for field, annotation in annotated_properties(rel, dto):
            if annotation == "SerialName":
                continue  # reported above, with a better message
            failures.append(
                f"  {rel}: `{dto}.{field}` carries `@{annotation}`, which is not in "
                f"`ALLOWED_DTO_ANNOTATIONS`.\n    An annotation can change what "
                f"kotlinx.serialization emits (`@Transient` drops the field, `@EncodeDefault` "
                f"changes whether a default is written) while the declaration this checker reads "
                f"stays identical. Decide what it means for the wire format and record it, rather "
                f"than letting it through unexamined."
            )


def check() -> int:
    allowlist = json.loads(ALLOWLIST.read_text(encoding="utf-8"))
    failures: list[str] = []

    check_versions(allowlist, failures)
    check_mirrored_constants(allowlist, failures)
    check_writer_encoders(allowlist, failures)

    # Everything below reads the writer's declaration, which lives in the contracts checkout since
    # 3.28.0. Without one it is skipped rather than failed: "the other repository is not checked
    # out" is not a drift signal. CI fetches the pinned contracts sources, and its schema-gate step
    # fails if any test reports a skip, so it is enforced where it counts.
    writer: dict[str, tuple[str, bool]] = {}
    if WRITER:
        writer = kotlin_data_class(WRITER, WRITER_DTO)
        check_reader(
            "jvm",
            JVM_READER,
            kotlin_data_class(JVM_READER, "DaemonLaunchDescriptor"),
            writer,
            allowlist,
            failures,
        )
        check_unknown_key_tolerance(allowlist, failures)
        check_raw_key_readers(writer, allowlist, failures)
        check_wire_fingerprint(writer, allowlist, failures)
        check_dto_declarations(failures)

    if failures:
        print("check-daemon-launch-schema: FAILED", file=sys.stderr)
        for f in failures:
            print(f, file=sys.stderr)
        return 1

    sites = len(allowlist["schemaVersionSites"])
    # Say what was skipped. A summary that claims a comparison it did not make is the failure this
    # gate exists to prevent, printed in its own output.
    if WRITER:
        print(
            f"check-daemon-launch-schema: OK — writer {WRITER_DTO} ({len(writer)} field(s)), "
            f"1 reader(s) agree (jvm), {sites} schema-version site(s) at v{writer_version()}"
        )
    else:
        print(
            f"check-daemon-launch-schema: OK — {sites} schema-version site(s) registered; writer "
            f"shape, jvm reader, raw-key reads and wire fingerprint SKIPPED — no "
            f"compose-preview-contracts checkout (set COMPOSE_PREVIEW_CONTRACTS_ROOT)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(check())
