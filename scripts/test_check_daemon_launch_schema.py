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

    def test_the_ts_reader_parses_and_marks_required_fields(self):
        t = mod.ts_interface(mod.TS_READER, "DaemonLaunchDescriptor")
        self.assertEqual(t["systemProperties"][0], "Record<string, string>")
        self.assertFalse(t["schemaVersion"][1], "schemaVersion is not declared optional")


class RealTree(unittest.TestCase):
    """The committed tree, which is the thing the gate actually protects."""

    @classmethod
    def setUpClass(cls):
        cls.allowlist = json.loads(
            (_HERE / "daemon-launch-schema-allowlist.json").read_text(encoding="utf-8")
        )

    def test_repo_is_green(self):
        self.assertEqual(mod.check(), 0)

    def test_every_registered_version_site_still_declares_its_symbol(self):
        for site in self.allowlist["schemaVersionSites"]:
            rel, symbol = site["file"], site["symbol"]
            consts = mod.ts_consts(rel) if rel.endswith(".ts") else mod.kotlin_consts(rel)
            self.assertIn(symbol, consts, f"{rel} no longer declares {symbol}")

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

    def test_each_reader_only_field_carries_a_default(self):
        jvm = mod.kotlin_data_class(mod.JVM_READER, "DaemonLaunchDescriptor")
        for field in self.allowlist["readerOnlyFields"]["jvm"]:
            self.assertIn(field, jvm)
            self.assertTrue(jvm[field][1], f"{field} must default — the plugin never writes it")

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

    def test_mirrored_constants_are_declared_where_the_register_says(self):
        for name, spec in self.allowlist["mirroredConstants"].items():
            self.assertIn(name, mod.kotlin_consts(spec["declaredBy"]))
            for rel in spec["mirroredBy"]:
                self.assertIn(spec.get("mirrorSymbol", name), mod.kotlin_consts(rel))


if __name__ == "__main__":
    unittest.main()
