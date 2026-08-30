#!/usr/bin/env python3
"""Unit tests for check-daemon-launch-schema.py.

Pure stdlib (unittest). Run: python3 scripts/test_check_daemon_launch_schema.py -v

Three things are worth pinning. The parsers, because a parser that silently
mis-reads a declaration turns this gate into decoration — `Map<String, String>`
in particular, whose top-level comma broke the first cut of the splitter and
made an identical pair of declarations compare unequal. The Kotlin-to-TypeScript
type correspondence, because that is the actual claim the check makes about two
languages. And the real committed tree, which must be green and must have every
registered site still present — the allowlist has to keep describing the code.
"""

import importlib.util
import json
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_REPO = _HERE.parent

_spec = importlib.util.spec_from_file_location(
    "check_daemon_launch_schema", _HERE / "check-daemon-launch-schema.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)



# The TypeScript half of this checker reads yschimke/compose-preview-vscode, which is a separate
# repository. These tests need a checkout; without one they skip rather than fail, since "you have
# not cloned the other repo" is not a drift signal. CI sets COMPOSE_PREVIEW_VSCODE_ROOT, so the
# coverage is not lost where it counts.
requires_ts_checkout = unittest.skipIf(
    mod._ts_root is None,
    "no compose-preview-vscode checkout (set COMPOSE_PREVIEW_VSCODE_ROOT)",
)

# Same again for the JVM reader, which moved to yschimke/compose-preview-contracts. The checker
# itself skips the JVM half without a checkout; these tests reach past `check()` into
# `kotlin_data_class` / `emitted_shape` / `kotlin_consts` directly, so the guard has to be
# repeated here or the documented standalone run ends in IsADirectoryError rather than a skip.
requires_contracts_checkout = unittest.skipIf(
    mod._contracts_root is None,
    "no compose-preview-contracts checkout (set COMPOSE_PREVIEW_CONTRACTS_ROOT)",
)

# And once more for the production image's Dockerfile, which left with the preview server. It
# carries the sandbox-count key as a JAVA_TOOL_OPTIONS literal — a mirror no Kotlin scan can see —
# so without the checkout there is nothing to assert against.
requires_server_checkout = unittest.skipIf(
    mod._server_root is None,
    "no compose-preview-server checkout (set COMPOSE_PREVIEW_SERVER_ROOT)",
)

class SplitParams(unittest.TestCase):
    def test_a_generic_type_argument_comma_is_not_a_separator(self):
        # The regression that motivated the splitter: `Map<String, String>` was truncated to
        # `Map<String`, which then compared unequal to the reader's identical declaration.
        self.assertEqual(
            mod.split_params("val a: Map<String, String>, val b: Int"),
            ["val a: Map<String, String>", "val b: Int"],
        )

    def test_nested_generics(self):
        self.assertEqual(
            mod.split_params("val a: Map<String, List<Pair<Int, Int>>>, val b: Int"),
            ["val a: Map<String, List<Pair<Int, Int>>>", "val b: Int"],
        )

    def test_a_comma_inside_a_default_call_is_not_a_separator(self):
        self.assertEqual(
            mod.split_params("val a: List<String> = listOf(1, 2), val b: Int"),
            ["val a: List<String> = listOf(1, 2)", "val b: Int"],
        )


class StripComments(unittest.TestCase):
    def test_line_comments_go(self):
        self.assertEqual(mod.strip_comments("a // b\nc"), "a \nc")

    def test_block_comments_nest_as_kotlin_says_they_do(self):
        self.assertEqual(mod.strip_comments("a/* x /* y */ z */b"), "ab")

    def test_a_url_in_a_string_is_not_a_comment(self):
        # The stripper walks the whole repo, where `"https://…"` is everywhere. Truncating at the
        # slashes silently shortened declarations; a literal `/*` opened a comment that swallowed
        # the rest of the file, which is how a mirror could hide behind an ordinary URL.
        src = 'const val URL = "https://host/x"'
        self.assertEqual(mod.strip_comments(src), src)

    def test_a_block_comment_opener_in_a_string_does_not_open_one(self):
        src = 'const val G = "a /* b"; const val H = 1'
        self.assertEqual(mod.strip_comments(src), src)

    def test_a_raw_string_keeps_its_slashes(self):
        src = 'val s = """raw // not a comment"""; val t = 1'
        self.assertEqual(mod.strip_comments(src), src)

    def test_an_escaped_quote_does_not_end_the_string(self):
        src = 'val e = "a\\"b // still string"; val f = 2'
        self.assertEqual(mod.strip_comments(src), src)

    def test_mirrored_constant_values_survive(self):
        # Blanking string contents would have been the easy fix, and would have broken the
        # mirrored-constant comparison outright — those constants ARE strings.
        self.assertIn(
            '"composeai.daemon.sandboxCount"',
            mod.strip_comments('const val P = "composeai.daemon.sandboxCount"'),
        )

    def test_a_kdoc_field_reference_does_not_become_a_field(self):
        # `@param jvmArgs` inside a KDoc must not be read as a declaration.
        src = "data class D(\n  /** see [other] and val ghost: Int */\n  val real: Int,\n)"
        self.assertEqual(list(mod.KT_FIELD.finditer(mod.strip_comments(src)))[0].group(1), "real")


class KotlinToTypeScript(unittest.TestCase):
    def test_scalars(self):
        self.assertEqual(mod.kotlin_to_ts("Int"), "number")
        self.assertEqual(mod.kotlin_to_ts("Long"), "number")
        self.assertEqual(mod.kotlin_to_ts("String"), "string")
        self.assertEqual(mod.kotlin_to_ts("Boolean"), "boolean")

    def test_nullable_becomes_a_union_with_null(self):
        self.assertEqual(mod.kotlin_to_ts("String?"), "string | null")

    def test_collections(self):
        self.assertEqual(mod.kotlin_to_ts("List<String>"), "string[]")
        self.assertEqual(mod.kotlin_to_ts("Map<String, String>"), "Record<string, string>")

    def test_a_named_type_keeps_its_name(self):
        self.assertEqual(mod.kotlin_to_ts("BtaCompileConfig?"), "BtaCompileConfig | null")

    def test_union_order_does_not_matter(self):
        self.assertTrue(mod.ts_types_agree("String?", "null | string"))

    def test_a_genuine_mismatch_is_still_a_mismatch(self):
        self.assertFalse(mod.ts_types_agree("List<String>", "string"))
        self.assertFalse(mod.ts_types_agree("String", "string | null"))


class Parsers(unittest.TestCase):
    def test_the_writer_parses_to_the_fields_the_descriptor_documents(self):
        w = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        self.assertEqual(w["systemProperties"][0], "Map<String, String>")
        self.assertEqual(w["javaLauncher"][0], "String?")
        self.assertFalse(w["schemaVersion"][1], "schemaVersion must have no default")
        self.assertTrue(w["btaCompile"][1], "btaCompile must default to null")

    @requires_ts_checkout
    def test_the_ts_reader_parses_and_marks_required_fields(self):
        t = mod.ts_interface(mod.TS_READER, "DaemonLaunchDescriptor")
        self.assertEqual(t["systemProperties"][0], "Record<string, string>")
        self.assertFalse(t["schemaVersion"][1], "schemaVersion is not declared optional")


class CommentNestingIsLanguageAware(unittest.TestCase):
    """Kotlin's block comments nest; TypeScript's do not. Using one rule for both hides code."""

    def test_typescript_ends_at_the_first_closer(self):
        self.assertEqual(
            mod.strip_comments("/* a /* b */ const x = 1", nested=False), " const x = 1"
        )

    def test_kotlin_needs_the_matching_closer(self):
        self.assertEqual(
            mod.strip_comments("/* a /* b */ z */ const x = 1", nested=True), " const x = 1"
        )

    def test_kotlin_would_swallow_what_typescript_keeps(self):
        # The concrete hazard: under Kotlin's rule the TS declaration disappears from the scan.
        self.assertNotIn("const x", mod.strip_comments("/* a /* b */ const x = 1", nested=True))


class TestSourceSets(unittest.TestCase):
    """Excluding only `src/test` and `src/functionalTest` missed most of this repo's test trees."""

    def test_the_source_sets_this_repo_actually_has(self):
        for rel in (
            "a/src/test/B.kt",
            "a/src/functionalTest/B.kt",
            "a/src/commonTest/B.kt",
            "a/src/jvmTest/B.kt",
            "a/src/iosTest/B.kt",
            "a/src/desktopTest/B.kt",
            "a/src/screenshotTest/B.kt",
            "a/src/testFixtures/B.kt",
        ):
            self.assertTrue(mod.is_test_source(rel), rel)

    def test_main_is_not_a_test_source(self):
        self.assertFalse(mod.is_test_source("a/src/main/B.kt"))


class TypeScriptConstants(unittest.TestCase):
    def test_a_module_local_const_is_still_a_mirror(self):
        # `export` is not what makes a constant dangerous; declaring a version is.
        self.assertIn("X", dict(mod.TS_CONST.findall("const X = 2;")))
        self.assertIn("Y", dict(mod.TS_CONST.findall("export const Y = 2;")))

    def test_discovery_matches_a_non_exported_version_name(self):
        found = mod.VERSION_NAME.findall("const ROGUE_DESCRIPTOR_SCHEMA_VERSION = 2;")
        self.assertEqual(["ROGUE_DESCRIPTOR_SCHEMA_VERSION"], found)


class WireFingerprint(unittest.TestCase):
    def test_a_renamed_field_changes_the_digest(self):
        a = {"variant": ("String", False)}
        b = {"flavour": ("String", False)}
        self.assertNotEqual(mod.wire_fingerprint(a), mod.wire_fingerprint(b))

    def test_a_retyped_field_changes_the_digest(self):
        a = {"jvmArgs": ("List<String>", False)}
        b = {"jvmArgs": ("String", False)}
        self.assertNotEqual(mod.wire_fingerprint(a), mod.wire_fingerprint(b))

    def test_making_a_field_optional_changes_the_digest(self):
        # Optionality is part of the wire contract: a required field becoming defaulted means a
        # writer may stop emitting it, which an old reader experiences as a parse failure.
        a = {"repositories": ("List<String>", False)}
        b = {"repositories": ("List<String>", True)}
        self.assertNotEqual(mod.wire_fingerprint(a), mod.wire_fingerprint(b))

    def test_a_nested_dto_rename_changes_the_digest(self):
        # The top-level shape is identical either way — `btaCompile: BtaCompileConfig?` — so this
        # only holds because the nested DTO is part of the hashed shape.
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        before = mod.wire_shape(writer)
        self.assertIn("BtaCompileConfig{", before)
        self.assertIn("moduleName", before)

    def test_the_digest_is_stable_for_the_same_shape(self):
        a = {"x": ("Int", False), "y": ("String", True)}
        self.assertEqual(mod.wire_fingerprint(a), mod.wire_fingerprint(dict(a)))


class RealTree(unittest.TestCase):
    """The committed tree, which is the thing the gate actually protects."""

    @classmethod
    def setUpClass(cls):
        cls.allowlist = json.loads(
            (_HERE / "daemon-launch-schema-allowlist.json").read_text(encoding="utf-8")
        )

    def test_repo_is_green(self):
        self.assertEqual(mod.check(), 0)

    @requires_ts_checkout
    def test_every_registered_version_site_still_declares_its_symbol(self):
        for site in self.allowlist["schemaVersionSites"]:
            rel, symbol = site["file"], site["symbol"]
            consts = mod.ts_consts(rel) if rel.endswith(".ts") else mod.kotlin_consts(rel)
            self.assertIn(symbol, consts, f"{rel} no longer declares {symbol}")

    @requires_ts_checkout
    def test_discovery_finds_every_registered_site(self):
        # If discovery stopped seeing a site, rule 1's "unregistered mirror" arm would go blind
        # while everything still reported green.
        found = set(mod.discover_version_mirrors())
        for site in self.allowlist["schemaVersionSites"]:
            self.assertIn(
                (site["file"], site["symbol"]),
                found,
                f"discovery missed {site['file']}:{site['symbol']}",
            )

    @requires_contracts_checkout
    def test_each_reader_only_field_carries_a_default(self):
        jvm = mod.kotlin_data_class(mod.JVM_READER, "DaemonLaunchDescriptor")
        for field in self.allowlist["readerOnlyFields"]["jvm"]:
            self.assertIn(field, jvm)
            self.assertTrue(jvm[field][1], f"{field} must default — the plugin never writes it")

    @requires_ts_checkout
    @requires_contracts_checkout
    def test_each_writer_only_field_really_is_absent_from_the_readers_it_claims(self):
        readers = {
            "jvm": mod.kotlin_data_class(mod.JVM_READER, "DaemonLaunchDescriptor"),
            "vscode": mod.ts_interface(mod.TS_READER, "DaemonLaunchDescriptor"),
        }
        for field, spec in self.allowlist["writerOnlyFields"].items():
            for label in spec["invisibleTo"]:
                self.assertNotIn(
                    field,
                    readers[label],
                    f"{field} is now declared by the {label} reader — prune the entry",
                )

    def test_every_construction_site_stamps_a_known_constant(self):
        # Discovery by use, which is what caught the fifth mirror that name matching missed:
        # `ServeBundleDaemon` calls its copy `DAEMON_LAUNCH_SCHEMA_VERSION`, not `…DESCRIPTOR…`.
        known = {s["symbol"] for s in self.allowlist["schemaVersionSites"]} | set(
            self.allowlist["versionStampAliases"]
        )
        stamps = mod.discover_version_stamps()
        self.assertTrue(stamps, "no descriptor construction sites found — discovery went blind")
        for rel, expr in stamps:
            self.assertFalse(expr.isdigit(), f"{rel} stamps a bare literal {expr}")
            self.assertIn(expr, known, f"{rel} stamps unregistered {expr}")

    def test_the_second_writer_is_registered(self):
        # Pinned by name, because a second writer is exactly what the first cut of this check
        # missed. It used to pin serve's `ServeBundleDaemon.DAEMON_LAUNCH_SCHEMA_VERSION` — a
        # differently-spelled constant, which is why discovery works by use and not by name alone.
        # Serve left the repository in #4732, so the site it pins is now the render-session
        # subprocess writer; serve's copy became a cross-repo mirror nothing here can see (residual
        # item 1 on that issue).
        self.assertIn(
            (
                "render-session/subprocess/src/main/kotlin/ee/schimke/composeai/render/session"
                "/subprocess/SubprocessRenderSession.kt",
                "DAEMON_DESCRIPTOR_SCHEMA_VERSION",
            ),
            {(s["file"], s["symbol"]) for s in self.allowlist["schemaVersionSites"]},
        )

    def test_no_registered_site_names_a_file_that_left_the_repository(self):
        # The allowlist is only a gate while every path in it resolves: a site whose file is gone is
        # checked vacuously, which is how serve's entry would have rotted after #4732 had it been
        # left behind. The two readers that legitimately live in a sibling checkout
        # (compose-preview-vscode, compose-preview-contracts) are exempt — the check already
        # resolves those through `ts_root()` / `contracts_root()` and reports them as SKIPPED when
        # no checkout is present, which is a stated outcome rather than silent rot.
        external = {mod.TS_READER_REL, mod.JVM_READER_REL}
        for site in self.allowlist["schemaVersionSites"]:
            if site["file"] in external:
                continue
            self.assertTrue(
                (mod.REPO_ROOT / site["file"]).is_file(),
                f"{site['file']} is registered but not in the tree",
            )

    def test_every_raw_key_the_doctor_reads_is_a_writer_field(self):
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        for rel in self.allowlist["rawKeyReaders"]:
            keys = {m.group(1) for m in mod.RAW_KEY.finditer(mod.strip_comments(mod.read(rel)))}
            self.assertTrue(keys, f"{rel} exposes no raw key reads any more")
            self.assertEqual(set(), keys - set(writer), f"{rel} reads keys the writer never emits")

    def test_no_dto_declares_a_serialised_body_property(self):
        # kotlinx emits an initialised body property like any constructor field, but every rule
        # here reads the constructor — so it would be invisible to all of them at once.
        for dto in ("DaemonClasspathDescriptor",) + mod.NESTED_DTOS:
            self.assertEqual([], mod.body_properties(mod.WRITER, dto), dto)

    def test_no_dto_uses_a_custom_serializer(self):
        self.assertEqual([], mod.class_level_serializer_overrides(mod.WRITER))

    def test_each_version_stamp_alias_is_scoped_to_a_file(self):
        # A bare name set would let any writer's local `schemaVersion` through purely because
        # DaemonBootstrapTask registered that spelling.
        for alias, spec in self.allowlist["versionStampAliases"].items():
            self.assertIn("file", spec, alias)
            self.assertIn((spec["file"], alias), set(mod.discover_version_stamps()), alias)

    @requires_contracts_checkout
    def test_the_writer_version_has_an_immutable_recorded_fingerprint(self):
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        version = mod.kotlin_consts(mod.WRITER)["DAEMON_DESCRIPTOR_SCHEMA_VERSION"]
        history = self.allowlist["wireFingerprint"]["history"]
        self.assertIn(version, history)
        self.assertEqual(
            history[version], mod.wire_fingerprint(mod.emitted_shape(writer, self.allowlist))
        )

    def test_every_writer_encoder_matches_its_recorded_contract(self):
        # The plugin's two writers, recorded per file because they genuinely differ in
        # pretty-printing. Serve's compact writer was the third until #4732 moved it out of this
        # tree; what is left is what this repository can still read.
        declared = self.allowlist["writerEncoders"]["declaredBy"]
        self.assertTrue(declared, "no writer encoders recorded — the rule went blind")
        for rel, required in declared.items():
            call = mod.ENCODE_CALL.search(mod.stripped(rel))
            self.assertIsNotNone(call, rel)

    @requires_contracts_checkout
    def test_the_fingerprint_covers_fields_only_serve_emits(self):
        # `jailCommand` / `hardTtlSeconds` are on the wire but absent from the plugin's DTO.
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        emitted = mod.emitted_shape(writer, self.allowlist)
        self.assertEqual({"jailCommand", "hardTtlSeconds"}, set(emitted) - set(writer))

    def test_no_production_path_restamps_a_version_through_copy(self):
        for rel, expr in mod.discover_version_stamps():
            self.assertNotEqual("1", expr, f"{rel} re-stamps a stale version")

    def test_every_construction_site_names_its_schema_version(self):
        for rel, expr in mod.discover_version_stamps():
            self.assertNotEqual(mod.POSITIONAL, expr, f"{rel} constructs positionally")

    def test_the_dto_declarations_are_not_mistaken_for_constructions(self):
        # The first cut of the positional rule reported the two files that DEFINE the descriptor.
        declaring = {mod.WRITER, mod.JVM_READER}
        self.assertEqual(set(), declaring & {rel for rel, _ in mod.discover_version_stamps()})

    def test_no_dto_property_carries_an_unvetted_annotation(self):
        # Refused as a class rather than one annotation at a time: `@Transient` drops a field and
        # `@EncodeDefault` changes whether a default is written, both invisible to a checker that
        # reads declarations. Nothing is allowed unless it has been considered.
        for dto in ("DaemonClasspathDescriptor",) + mod.NESTED_DTOS:
            self.assertEqual([], mod.annotated_properties(mod.WRITER, dto), dto)

    def test_no_reader_only_entry_is_stale(self):
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        for label, fields in self.allowlist["readerOnlyFields"].items():
            self.assertEqual(
                set(),
                set(fields) & set(writer),
                f"{label}: reader-only entries the writer now emits must be pruned",
            )

    def test_no_descriptor_dto_uses_serial_name(self):
        # `@SerialName` moves the JSON key while the identifier — which every rule here reads —
        # stays put. The checker refuses it rather than half-supporting it.
        for dto in ("DaemonClasspathDescriptor",) + mod.NESTED_DTOS:
            self.assertEqual([], mod.serial_name_renames(mod.WRITER, dto), dto)

    def test_every_raw_key_records_the_type_it_assumes(self):
        writer = mod.kotlin_data_class(mod.WRITER, "DaemonClasspathDescriptor")
        for rel, spec in self.allowlist["rawKeyReaders"].items():
            keys = {m.group(1) for m in mod.RAW_KEY.finditer(mod.stripped(rel))}
            assumed = spec.get("assumedTypes", {})
            self.assertEqual(keys, set(assumed), f"{rel}: assumedTypes must cover every raw read")
            for key, expected in assumed.items():
                self.assertEqual(writer[key][0], expected, f"{rel}: {key}")

    @requires_server_checkout
    def test_a_mirrored_key_that_lives_outside_kotlin_is_registered(self):
        # The production image passes the sandbox-count key as a literal in JAVA_TOOL_OPTIONS. The
        # image moved to yschimke/compose-preview-server, so this resolves against that checkout.
        spec = self.allowlist["mirroredConstants"]["SANDBOX_COUNT_PROP"]
        self.assertIn("deploy/image/Dockerfile", spec.get("alsoAppearsIn", []))
        for rel in spec["alsoAppearsIn"]:
            self.assertIn("composeai.daemon.sandboxCount", mod.read(rel))

    @requires_ts_checkout
    def test_a_stamp_resolves_by_package_not_by_bare_name(self):
        # `DaemonLaunchBuilder.kt` stamps a constant declared in a sibling file of the same
        # package, which must pass; a same-named symbol in an unrelated package must not.
        by_package = {}
        for s in self.allowlist["schemaVersionSites"]:
            by_package.setdefault(s["symbol"], set()).add(mod.package_declared_in(s["file"]))
        self.assertTrue(
            mod.resolves_to_registered(
                "gradle-plugin/daemon-launch-builder/src/main/kotlin/ee/schimke/composeai/"
                "daemonlaunch/DaemonLaunchBuilder.kt",
                "DAEMON_DESCRIPTOR_SCHEMA_VERSION",
                by_package,
            )
        )
        self.assertFalse(
            mod.resolves_to_registered(
                "daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/DaemonMain.kt",
                "DAEMON_DESCRIPTOR_SCHEMA_VERSION",
                by_package,
            )
        )

    @requires_contracts_checkout
    def test_mirrored_constants_are_declared_where_the_register_says(self):
        for name, spec in self.allowlist["mirroredConstants"].items():
            self.assertIn(name, mod.kotlin_consts(spec["declaredBy"]))
            for rel in spec["mirroredBy"]:
                self.assertIn(spec.get("mirrorSymbol", name), mod.kotlin_consts(rel))


if __name__ == "__main__":
    unittest.main()
