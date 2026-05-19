"""Starlark rules wrapping `compose-preview` for Bazel.

This is the resources-only stepping stone — see issue #1253. Only the
`discover_resources` rule exists today; `render_resources` arrives once
the Robolectric render path is invocable outside Gradle (#1037).

Today the implementation invokes `_discover.sh`, a hermetic shell action
that walks the declared `srcs` and emits a `resources.json` matching
the wire format produced by the Gradle plugin's
`discoverAndroidResources` task. It will be swapped for a
`compose-preview discover-resources` action once that subcommand
lands; the rule's interface (`srcs`, `module`, `variant`, output
`name.json`) is what we're committing to here.
"""

def _discover_resources_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".json")
    args = ctx.actions.args()
    args.add(ctx.file._discover.path)
    args.add(out.path)
    args.add(ctx.attr.module)
    args.add(ctx.attr.variant)
    args.add_all([f.path for f in ctx.files.srcs])

    # `run_shell` instead of `run(executable=sh_binary)`: native
    # sh_binary was removed in Bazel 8 and now lives in `@rules_shell`.
    # For a placeholder action, invoking `bash "$@"` directly avoids
    # taking a third-party module dep for one shell script — the real
    # `compose-preview discover-resources` CLI will replace this.
    ctx.actions.run_shell(
        command = 'bash "$@"',
        arguments = [args],
        inputs = ctx.files.srcs + [ctx.file._discover],
        outputs = [out],
        mnemonic = "ComposePreviewDiscoverResources",
        progress_message = "Discovering Compose resources for %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

discover_resources = rule(
    implementation = _discover_resources_impl,
    doc = """Walks `srcs` (Android `res/` XML files) and writes a
    `<name>.json` manifest matching the `resources.json` schema
    produced by the Gradle plugin.

    Outputs:
      <name>.json — the resources manifest. Path is module-relative
      from `srcs[0]`'s package; see issue #1253 for the spec.""",
    attrs = {
        "srcs": attr.label_list(
            allow_files = [".xml"],
            mandatory = True,
            doc = "Android resource XML files. Directory layout " +
                  "(`drawable/`, `drawable-night/`, `mipmap-anydpi-v26/`) " +
                  "is parsed from the file paths.",
        ),
        "module": attr.string(
            mandatory = True,
            doc = "Logical module name written into `resources.json`. " +
                  "Matches the Gradle plugin's per-module manifest.",
        ),
        "variant": attr.string(
            default = "main",
            doc = "Variant label (Gradle parity). Bazel sample uses 'main'.",
        ),
        "_discover": attr.label(
            default = ":_discover.sh",
            allow_single_file = [".sh"],
        ),
    },
)
