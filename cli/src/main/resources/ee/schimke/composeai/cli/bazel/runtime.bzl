"""Collect a configured JVM target's render runtime, without adding a preview rule.

JavaInfo loading is the only tool/version-specific part; replace java_info.bzl
for an internal provider. No IntelliJ plugin or rules_kotlin internals required.
"""

load(":java_info.bzl", "JavaInfo")

def _impl(target, ctx):
    if JavaInfo not in target:
        fail("compose-preview needs a JVM target providing JavaInfo: %s" % target.label)
    java = target[JavaInfo]
    project_jars = java.runtime_output_jars
    if not project_jars:
        fail("compose-preview target has no application runtime jars: %s" % target.label)
    runtime = depset(project_jars, transitive = [java.transitive_runtime_jars])
    runtime_files = runtime.to_list()
    runtime_paths = {f.path: True for f in runtime_files}
    sources = [f for f in getattr(ctx.rule.files, "srcs", []) if f.extension in ["kt", "java"]]
    runfiles = target[DefaultInfo].default_runfiles.files
    metadata = ctx.actions.declare_file(target.label.name + ".compose-preview.json")
    ctx.actions.write(metadata, json.encode({
        "schemaVersion": 1,
        "target": str(target.label),
        # Includes Bazel's configured output-directory key; never combine configurations.
        "configuration": ctx.bin_dir.path,
        "projectJars": [f.path for f in project_jars],
        "runtimeJars": [f.path for f in runtime_files],
        "sourceFiles": [f.path for f in sources],
        "dataFiles": [f.path for f in runfiles.to_list() if f.path not in runtime_paths],
    }))
    return [OutputGroupInfo(
        compose_preview_metadata = depset([metadata]),
        # Request this group to build/materialize runtime-only/generated inputs too.
        compose_preview_runtime = depset(sources, transitive = [runtime, runfiles]),
    )]

# JavaInfo already carries the runtime closure, including runtime_deps. Traversing
# every dependency would emit unrelated modules and toolchains into discovery.
compose_preview_aspect = aspect(implementation = _impl)
