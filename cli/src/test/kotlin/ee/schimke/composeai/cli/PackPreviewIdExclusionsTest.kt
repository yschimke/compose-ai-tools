package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class PackPreviewIdExclusionsTest {

  private val tmpRoot: java.io.File =
    java.nio.file.Files.createTempDirectory("pack-id-exclusions-").toFile().also {
      it.deleteOnExit()
    }

  /**
   * The id shape that motivated the file flag: `@Preview(widthDp = …, heightDp = …)` mints an id
   * with commas in it, so the comma-separated flag cannot carry one.
   */
  private val commaBearingIds =
    listOf(
      "ee.schimke.wearm3catalog.remote.CatalogPreviewsKt." +
        "CustomShapeRemoteButton_width=227dp, height=100dp, dpi=320",
      "ee.schimke.wearm3catalog.remote.CatalogPreviewsKt." +
        "NamedLabelRemoteButton_width=227dp, height=100dp, dpi=320",
    )

  private fun fileWith(lines: List<String>): java.io.File =
    java.io.File.createTempFile("excludes-", ".txt", tmpRoot).apply {
      deleteOnExit()
      writeText(lines.joinToString("\n"))
    }

  @Test
  fun `a file carries an id that contains commas, intact`() {
    val f = fileWith(commaBearingIds)
    assertEquals(
      commaBearingIds,
      PackPreviewIdExclusions.fromArgs(
        listOf("pack", "--exclude-preview-id-file", f.path),
        noEnv,
      ),
    )
  }

  @Test
  fun `the comma flag shatters the same ids - the regression the file flag exists for`() {
    // Not aspirational: this is what `--exclude-preview-id` does, and why it must not be used for
    // a generated list. Each id becomes three fragments.
    val shattered =
      PackPreviewIdExclusions.fromArgs(
        listOf("pack", "--exclude-preview-id", commaBearingIds.joinToString(",")),
        noEnv,
      )
    assertEquals(6, shattered.size)
    assertEquals(true, shattered.contains("dpi=320"))
  }

  @Test
  fun `a shattered fragment excludes every preview, but the file form excludes only its own`() {
    // The live failure: `dpi=320` is a substring of every id in the module, and a plain pattern
    // matches on substring — so the render was left with nothing to draw.
    val all = commaBearingIds + listOf("ee.schimke.Other.ThirdSticker_width=227dp, dpi=320")
    val shattered =
      PackPreviewIdExclusions.fromArgs(
        listOf("pack", "--exclude-preview-id", commaBearingIds.joinToString(",")),
        noEnv,
      )
    assertEquals(emptyList(), PackPreviewIdExclusions.retain(all, shattered))

    val f = fileWith(commaBearingIds)
    val fromFile =
      PackPreviewIdExclusions.fromArgs(listOf("pack", "--exclude-preview-id-file", f.path), noEnv)
    assertEquals(listOf(all[2]), PackPreviewIdExclusions.retain(all, fromFile))
  }

  @Test
  fun `the file wins over both the flag and the env var`() {
    val f = fileWith(listOf("OnlyThis"))
    assertEquals(
      listOf("OnlyThis"),
      PackPreviewIdExclusions.fromArgs(
        listOf("pack", "--exclude-preview-id-file", f.path, "--exclude-preview-id", "Ignored"),
        envWith("AlsoIgnored"),
      ),
    )
  }

  @Test
  fun `blank lines are dropped and entries trimmed`() {
    val f = fileWith(listOf("  Foo_Dark  ", "", "   ", "Bar_Dark"))
    assertEquals(
      listOf("Foo_Dark", "Bar_Dark"),
      PackPreviewIdExclusions.fromArgs(listOf("pack", "--exclude-preview-id-file", f.path), noEnv),
    )
  }

  @Test
  fun `a missing file fails loudly rather than excluding nothing`() {
    val missing = java.io.File(tmpRoot, "nope.txt")
    val e =
      kotlin
        .runCatching {
          PackPreviewIdExclusions.fromArgs(
            listOf("pack", "--exclude-preview-id-file", missing.path),
            noEnv,
          )
        }
        .exceptionOrNull()
    assertEquals(true, e is IllegalStateException)
    assertEquals(true, e!!.message!!.contains("not a readable file"))
  }

  private val noEnv: (String) -> String? = { null }

  private fun envWith(value: String): (String) -> String? = { name ->
    if (name == PackPreviewIdExclusions.ENV_VAR) value else null
  }

  @Test
  fun `no flag and no env yields no patterns`() {
    assertEquals(
      emptyList(),
      PackPreviewIdExclusions.fromArgs(listOf("pack", "--module", "app"), noEnv),
    )
  }

  @Test
  fun `repeatable flag collects every value`() {
    val args = listOf("--exclude-preview-id", "Foo_Dark", "--exclude-preview-id", "Bar_Dark")
    assertEquals(listOf("Foo_Dark", "Bar_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `one value may be comma-separated`() {
    val args = listOf("--exclude-preview-id", "Foo_Dark, Bar_Dark ,")
    assertEquals(listOf("Foo_Dark", "Bar_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `env var is used when the flag is absent`() {
    assertEquals(
      listOf("Foo_Dark", "Bar_Dark"),
      PackPreviewIdExclusions.fromArgs(listOf("pack"), envWith("Foo_Dark,Bar_Dark")),
    )
  }

  @Test
  fun `an explicit flag wins over the env var rather than merging`() {
    assertEquals(
      listOf("Foo_Dark"),
      PackPreviewIdExclusions.fromArgs(
        listOf("--exclude-preview-id", "Foo_Dark"),
        envWith("Everything_Dark"),
      ),
    )
  }

  @Test
  fun `env var name is the Gradle property's ORG_GRADLE_PROJECT form`() {
    assertEquals("ORG_GRADLE_PROJECT_composePreview.idExclude", PackPreviewIdExclusions.ENV_VAR)
  }

  @Test
  fun `row labels come from their own flag and env var`() {
    val args =
      listOf("--exclude-preview-row", "Dark, ExtraDark", "--exclude-preview-id", "Foo_Dark")
    assertEquals(listOf("Dark", "ExtraDark"), PackPreviewIdExclusions.rowsFromArgs(args, noEnv))
    // The two axes are independent: an id pattern never lands in the row list, or a pack would skip
    // rows it was only asked to skip whole previews for.
    assertEquals(listOf("Foo_Dark"), PackPreviewIdExclusions.fromArgs(args, noEnv))
  }

  @Test
  fun `row labels fall back to the row env var only`() {
    val env = { name: String ->
      when (name) {
        PackPreviewIdExclusions.ROW_ENV_VAR -> "Dark"
        PackPreviewIdExclusions.ENV_VAR -> "Foo_Dark"
        else -> null
      }
    }
    assertEquals(listOf("Dark"), PackPreviewIdExclusions.rowsFromArgs(listOf("pack"), env))
    assertEquals(
      "ORG_GRADLE_PROJECT_composePreview.rowExclude",
      PackPreviewIdExclusions.ROW_ENV_VAR,
    )
  }

  private val ids = listOf("Foo_Light", "Foo_Dark", "FooLarge_Dark", "Bar_Light")

  @Test
  fun `no patterns retains every id`() {
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, emptyList()))
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, listOf(" ", "")))
  }

  @Test
  fun `an exact pattern drops that id`() {
    assertEquals(
      listOf("Foo_Light", "FooLarge_Dark", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("Foo_Dark")),
    )
  }

  @Test
  fun `a plain pattern also matches as a substring, like the plugin's matchesId`() {
    // The render-side filter (PreviewNameFilter.matchesId) substring-matches a glob-free pattern,
    // so
    // this must too — otherwise a plain pattern would thin the render but not the semantics pass.
    assertEquals(
      listOf("Foo_Light", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("_Dark")),
    )
  }

  @Test
  fun `a glob drops the whole family`() {
    assertEquals(
      listOf("Foo_Light", "Bar_Light"),
      PackPreviewIdExclusions.retain(ids, listOf("*_Dark")),
    )
  }

  @Test
  fun `matching is case-sensitive`() {
    assertEquals(ids, PackPreviewIdExclusions.retain(ids, listOf("foo_dark")))
  }

  @Test
  fun `a substring pattern is as wide as it reads`() {
    // `Foo` matches `Foo_Light`, `Foo_Dark` AND `FooLarge_Dark` — the same width the render-side
    // filter gives it. Reach for a glob (`Foo_*`) when that's not what you meant.
    assertEquals(listOf("Bar_Light"), PackPreviewIdExclusions.retain(ids, listOf("Foo")))
  }

  @Test
  fun `an anchored pattern drops only the id it names, not its fan-out`() {
    // Issue #3559: ids are hierarchical, so a base id is a substring of its own variants.
    // Unanchored, excluding a base id also dropped every variant of it — which is what silently
    // cost a sharded render three quarters of its captures.
    val ids = listOf("FilledButton_Light", "FilledButton_Light_VARIANT_off", "FilledButton_Dark")

    assertEquals(
      listOf("FilledButton_Dark"),
      PackPreviewIdExclusions.retain(ids, listOf("FilledButton_Light")),
    )
    assertEquals(
      listOf("FilledButton_Light_VARIANT_off", "FilledButton_Dark"),
      PackPreviewIdExclusions.retain(ids, listOf("=FilledButton_Light")),
    )
  }

  // --- --id-file: the include-side twin (the composePreviewBundle failure) ---

  @Test
  fun `an id file carries a comma-bearing id intact`() {
    val f = fileWith(commaBearingIds)
    assertEquals(
      commaBearingIds,
      PackPreviewIdExclusions.idFileFromArgs(listOf("pack", "--id-file", f.path))!!.let(
        PackPreviewIdExclusions::linesOf
      ),
    )
  }

  @Test
  fun `no --id-file yields null, leaving --id in charge`() {
    assertEquals(null, PackPreviewIdExclusions.idFileFromArgs(listOf("pack", "--id", "Foo")))
  }

  /**
   * The live failure: `--id` comma-splits, so the first fragment is what `composePreviewBundle`
   * reports as `preview id not found`. Pinned so the shattering is a documented behaviour of the
   * flag rather than a surprise.
   *
   * Through [PackPreviewIdExclusions.selectedIds] — the selection `bundle pack` actually makes.
   * This test used to rebuild `args.drop(2).flatMap { it.split(',') }` by hand, which would keep
   * passing if the real parsing stopped splitting, changed its trimming, or lost the file's
   * precedence over the flag: a regression test that cannot see the code it guards.
   */
  @Test
  fun `--id shatters a comma-bearing id into its fragments`() {
    val shattered = PackPreviewIdExclusions.selectedIds(listOf("pack", "--id", commaBearingIds[0]))
    assertEquals(3, shattered.size)
    assertEquals(
      "ee.schimke.wearm3catalog.remote.CatalogPreviewsKt.CustomShapeRemoteButton_width=227dp",
      shattered[0],
    )
  }

  @Test
  fun `an id file survives the comma that shatters --id, and outranks it`() {
    // The two halves the hand-rolled version could not reach: the file keeps a comma-bearing id
    // whole, and having both forms is not a merge — the file wins, because it is the one that
    // survives a comma.
    val file = fileWith(commaBearingIds)
    assertEquals(
      commaBearingIds,
      PackPreviewIdExclusions.selectedIds(listOf("pack", "--id-file", file.path)),
    )
    assertEquals(
      commaBearingIds,
      PackPreviewIdExclusions.selectedIds(
        listOf("pack", "--id", "SomethingElse", "--id-file", file.path)
      ),
    )
  }

  @Test
  fun `repeated --id flags accumulate, and blanks are dropped`() {
    assertEquals(
      listOf("Alpha", "Beta", "Gamma"),
      PackPreviewIdExclusions.selectedIds(
        listOf("pack", "--id", " Alpha , Beta ", "--id", "", "--id", "Gamma")
      ),
    )
  }

  @Test
  fun `no selector at all selects nothing, which the caller reads as every preview`() {
    assertEquals(emptyList(), PackPreviewIdExclusions.selectedIds(listOf("pack", "--out", "x.zip")))
  }

  @Test
  fun `a missing id file fails loudly rather than packing nothing`() {
    val missing = java.io.File(tmpRoot, "nope-ids.txt")
    val e =
      kotlin
        .runCatching {
          PackPreviewIdExclusions.linesOf(
            PackPreviewIdExclusions.idFileFromArgs(listOf("pack", "--id-file", missing.path))!!
          )
        }
        .exceptionOrNull()
    assertEquals(true, e is IllegalStateException)
  }

  // --- an empty file is not "no selection" ------------------------------------------------------

  /**
   * The trap: for BOTH flags an empty list reads as *everything*, not nothing. `--id-file` leaves
   * `-PbundlePreviewIds` unset and `bundle pack` packs the whole catalog; an empty exclusion list
   * excludes nothing and the whole sheet renders. A generated shard file that came out empty would
   * silently do the opposite of what it asked, on every shard, and report success.
   */
  @Test
  fun `a present but empty file is refused rather than read as no selection`() {
    for (content in listOf("", "\n", "   \n\t\n   ")) {
      val f = fileWith(emptyList()).apply { writeText(content) }
      val e = kotlin.runCatching { PackPreviewIdExclusions.linesOf(f) }.exceptionOrNull()
      assertEquals(
        true,
        e is IllegalStateException,
        "expected a throw for ${content.length} blank char(s)",
      )
      assertEquals(true, e!!.message!!.contains("contains no preview ids"))
    }
  }

  @Test
  fun `a file with one id among blank lines is still a selection`() {
    val f = fileWith(listOf("", "   ", commaBearingIds[0], ""))
    assertEquals(listOf(commaBearingIds[0]), PackPreviewIdExclusions.linesOf(f))
  }
}
