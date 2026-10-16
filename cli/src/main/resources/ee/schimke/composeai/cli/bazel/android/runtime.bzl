"""Collect a configured JVM target's render runtime, without adding a preview rule.

JavaInfo loading is the only tool/version-specific part; replace java_info.bzl
for an internal provider. No IntelliJ plugin or rules_kotlin internals required.
"""

load(":java_info.bzl", "JavaInfo")

def _json_path(file):
    return json.encode(file.path)

def _json_owner(file):
    # The target that produced each jar (File.owner): names the coordinate of a jar whose name and
    # path hide it, and the target to rebuild for exact bytes in this workspace.
    if not file.owner:
        return None
    return json.encode(file.path) + ":" + json.encode(str(file.owner))

def _json_member(args, key, values, map_each = _json_path, brackets = "[]", last = False):
    args.add_joined(
        values,
        map_each = map_each,
        join_with = ",",
        format_joined = json.encode(key) + ":" + brackets[0] + "%s" + brackets[1] + ("" if last else ","),
        omit_if_empty = False,
        # A tree artifact is one entry, named by its directory, as File.path names it.
        expand_directories = False,
    )

def compose_preview_metadata_args(
        ctx,
        schema_version,
        target,
        project_jars,
        runtime,
        source_files,
        data_files,
        extra = None):
    """The artifacts metadata JSON as an Args object, for ctx.actions.write.

    Internal to these rules (defs.bzl and android.bzl share it); not a public API.

    The runtime closure is passed as a depset and only expanded when the file is written, so
    analysis neither flattens it nor retains an O(classpath) string per target.

    Args:
      ctx: the rule or aspect context.
      schema_version: the artifacts schema version.
      target: the Label the metadata describes.
      project_jars: list or depset of the target's own runtime jars.
      runtime: depset of every runtime jar, in classpath order.
      source_files: list or depset of the target's source files.
      data_files: list or depset of the runtime's data files.
      extra: dict of further top-level members (small), or None.

    Returns:
      An Args object whose lines form one JSON object.
    """
    args = ctx.actions.args()

    # One element per line, verbatim: the elements are JSON fragments, never shell words.
    args.set_param_file_format("multiline")
    args.add("{")
    args.add("%s:%d," % (json.encode("schemaVersion"), schema_version))
    args.add("%s:%s," % (json.encode("target"), json.encode(str(target))))

    # Includes Bazel's configured output-directory key; never combine configurations.
    args.add("%s:%s," % (json.encode("configuration"), json.encode(ctx.bin_dir.path)))
    _json_member(args, "projectJars", project_jars)
    _json_member(args, "runtimeJars", runtime)
    _json_member(args, "runtimeJarOwners", runtime, map_each = _json_owner, brackets = "{}")
    _json_member(args, "sourceFiles", source_files)
    _json_member(args, "dataFiles", data_files, last = True)
    for key, value in (extra or {}).items():
        args.add(",%s:%s" % (json.encode(key), json.encode(value)))
    args.add("}")
    return args

def _impl(target, ctx):
    if JavaInfo not in target:
        fail("compose-preview needs a JVM target providing JavaInfo: %s" % target.label)
    java = target[JavaInfo]
    project_jars = java.runtime_output_jars
    if not project_jars:
        fail("compose-preview target has no application runtime jars: %s" % target.label)
    runtime = depset(project_jars, transitive = [java.transitive_runtime_jars])
    sources = [f for f in getattr(ctx.rule.files, "srcs", []) if f.extension in ["kt", "java"]]
    runfiles = target[DefaultInfo].default_runfiles.files

    # dataFiles is the runfiles less the runtime jars: a set difference, which Args cannot
    # express lazily. So both depsets are flattened here, once, and only the filtered list (the
    # runfiles that are not jars on the classpath, usually few) outlives analysis.
    runtime_paths = {f.path: True for f in runtime.to_list()}
    data_files = [f for f in runfiles.to_list() if f.path not in runtime_paths]
    metadata = ctx.actions.declare_file(target.label.name + ".compose-preview.json")
    ctx.actions.write(metadata, compose_preview_metadata_args(
        ctx,
        schema_version = 1,
        target = target.label,
        project_jars = project_jars,
        runtime = runtime,
        source_files = sources,
        data_files = data_files,
    ))
    return [OutputGroupInfo(
        compose_preview_metadata = depset([metadata]),
        # Request this group to build/materialize runtime-only/generated inputs too.
        compose_preview_runtime = depset(sources, transitive = [runtime, runfiles]),
    )]

# JavaInfo already carries the runtime closure, including runtime_deps. Traversing
# every dependency would emit unrelated modules and toolchains into discovery.
compose_preview_aspect = aspect(implementation = _impl)
