"""Collect an Android Compose target's render inputs, without adding a preview rule.

Extends runtime.bzl's JVM metadata (artifacts schema 1) with the linked resources
a Robolectric render needs (schema 2, compose-preview-contracts'
build-artifacts-protocol): a standalone resource APK (with the transitive assets
packed under assets/), the manifest it was linked against and R classes carrying
the final IDs of that APK.

An android_library never links its resources; only binaries and
android_local_test do. This aspect therefore links them itself with the SDK's
aapt2, using only the public resources provider and the Android SDK toolchain,
so it does not depend on rules_android's internal resource pipeline. Outputs live
under `_compose_preview/` and never collide with the target's own.

Two aspects, so the walk over a large graph stays cheap:
- compose_preview_android_collector propagates along deps and exports. It carries
  no tools and flattens nothing; each target only adds its own jars and resource
  archives to depsets.
- compose_preview_android_aspect is the one the CLI names. It does not propagate:
  it requires the collector, and links and writes metadata once, for the target
  it is applied to. Its tools (link.sh, the JDK, the SDK toolchain) are therefore
  dependencies of the selected target only, never of the libraries it reaches.
"""

load(":android_providers.bzl", "ANDROID_SDK_TOOLCHAIN_TYPE", "AndroidIdeInfo", "StarlarkAndroidResourcesInfo")
load(":java_info.bzl", "JDK_RUNTIME", "JavaInfo", "JavaRuntimeInfo")
load(":runtime.bzl", "compose_preview_metadata_args")

_OwnJarsInfo = provider(
    doc = "A target's own compiled jars and sources, including those of targets it exports.",
    fields = {
        "jars": "depset of File",
        "sources": "depset of File: the .kt and .java sources the jars were compiled from",
    },
)

_ResourceOrderInfo = provider(
    doc = "Compiled resource archives and assets in overlay order: every dependency before its dependents.",
    fields = {
        "compiled": "postorder depset of File",
        "assets": "postorder depset of (path inside assets/, File) tuples",
    },
)

def _node_assets(node):
    # An asset's path inside the APK is relative to its library's assets_dir.
    assets = getattr(node, "assets", None)
    if not assets:
        return []
    root = "/".join([p for p in (node.label.workspace_root, node.label.package, getattr(node, "assets_dir", None) or "") if p])
    out = []
    for f in assets.to_list():
        if root and (f.path == root or f.path.endswith("/" + root)):
            # A generated directory standing for the whole assets_dir.
            out.append(("", f))
            continue
        i = f.path.find(root + "/") if root else -1
        out.append((f.path[i + len(root) + 1:] if i >= 0 else f.basename, f))
    return out

def _resource_order(target, ctx):
    # rules_android's own depsets are preorder (a library before its dependencies) and mix
    # dependencies' nodes into "direct", so no flattening of them yields aapt2's overlay order.
    # Build it here instead: postorder over deps and exports puts the target's archive last.
    own = []
    own_assets = []
    if StarlarkAndroidResourcesInfo in target:
        for node in target[StarlarkAndroidResourcesInfo].direct_resources_nodes.to_list():
            if node.label != target.label:
                continue
            if node.compiled_resources:
                own.append(node.compiled_resources)
            own_assets += _node_assets(node)
    children = []
    for name in ("deps", "exports"):
        value = getattr(ctx.rule.attr, name, None)
        if type(value) == "list":
            children += [d[_ResourceOrderInfo] for d in value if _ResourceOrderInfo in d]
    return _ResourceOrderInfo(
        compiled = depset(own, transitive = [c.compiled for c in children], order = "postorder"),
        assets = depset(own_assets, transitive = [c.assets for c in children], order = "postorder"),
    )

def _own_jars(target, ctx):
    # A wrapper that only re-exports its implementation (kt_android_library, a macro's umbrella
    # target) has no srcs of its own: its sources, like its jars, are those of what it exports.
    exported = [
        dep[_OwnJarsInfo]
        for dep in getattr(ctx.rule.attr, "exports", [])
        if _OwnJarsInfo in dep
    ]
    direct = target[JavaInfo].runtime_output_jars if JavaInfo in target else []
    sources = [f for f in getattr(ctx.rule.files, "srcs", []) if f.extension in ["kt", "java"]]
    return _OwnJarsInfo(
        jars = depset(direct, transitive = [e.jars for e in exported]),
        sources = depset(sources, transitive = [e.sources for e in exported]),
    )

def _compile_sdk(android_jar):
    # A heuristic, like _aapt2_binary below: both read the SDK's directory layout, which no
    # provider states. A missing level is allowed; the CLI then reads it from the SDK platform
    # or android.jar itself (compose-ai-tools' docs/BAZEL.md). _aapt2_binary falls back to the
    # toolchain's own aapt2 when the layout names no build-tools directory.
    #
    # Any `android-<level>` segment of the jar's path (.../platforms/android-36/android.jar); null
    # when the SDK layout names none, which the schema allows.
    path = android_jar.path
    for i in range(len(path)):
        if path[i:i + len("android-")] != "android-":
            continue
        digits = ""
        for c in path[i + len("android-"):].elems():
            if not c.isdigit():
                break
            digits += c
        if digits:
            return int(digits)
    return None

def _aapt2_binary(sdk):
    # The toolchain's aapt2 is a launcher that finds the real binary (and the lib64 it loads)
    # through its runfiles, which exist only inside Bazel. The SDK repository is local, so its own
    # binary is always on disk; link.sh puts its lib64 on LD_LIBRARY_PATH as the launcher does.
    root, separator, _ = sdk.android_jar.path.partition("/platforms/")
    version = getattr(sdk, "build_tools_version", None)
    if separator and version:
        return root + "/build-tools/" + version + "/aapt2"
    return sdk.aapt2.executable.path

def _own_node_first(target, resources):
    nodes = resources.direct_resources_nodes.to_list()
    return [n for n in nodes if n.label == target.label] + [n for n in nodes if n.label != target.label]

def _manifest(ctx, target, resources):
    # Prefer the target's own manifest over those of the targets it exports.
    for node in _own_node_first(target, resources):
        if node.manifest:
            return node.manifest
    manifest = ctx.actions.declare_file("_compose_preview/%s/AndroidManifest.xml" % target.label.name)
    ctx.actions.write(manifest, """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s">
  <application />
</manifest>
""" % resources.package)
    return manifest

def _link(ctx, target, resources, order):
    sdk = ctx.toolchains[ANDROID_SDK_TOOLCHAIN_TYPE].android_sdk_info
    java_runtime = ctx.attr._jdk[JavaRuntimeInfo]
    prefix = "_compose_preview/%s/" % target.label.name
    manifest = _manifest(ctx, target, resources)
    apk = ctx.actions.declare_file(prefix + "resources.ap_")
    r_jar = ctx.actions.declare_file(prefix + "r-final.jar")

    # The link needs real lists: aapt2's overlay order and the asset override below depend on
    # the order and on dedupe across depsets, which no depset expresses. Each depset is
    # flattened exactly once, here, and only for the selected target (the collector flattens
    # nothing).
    #
    # Overlay order (dependencies first, the target's own archive last) comes from the aspect's
    # own traversal. Archives it did not reach (a dependency through an attribute other than
    # deps/exports) go first, as the most distant.
    ordered = order.compiled.to_list()
    seen = {f: True for f in ordered}
    unordered = []
    for f in resources.transitive_compiled_resources.to_list() + resources.direct_compiled_resources.to_list():
        if f not in seen:
            seen[f] = True
            unordered.append(f)
    compiled = unordered + ordered

    # Assets staged into one directory in the same order, so a target's own asset replaces a
    # dependency's at the same path, as an APK merge would.
    staged = {}
    for path, f in order.assets.to_list():
        staged[path] = f
    assets = ctx.actions.declare_file(prefix + "assets.txt")

    # Source first: tab is IFS whitespace, so an empty (whole-directory) path must come last.
    ctx.actions.write(assets, "".join(["%s\t%s\n" % (f.path, path) for path, f in staged.items()]))

    # Libraries without a resource package (an aar_import of a resource-less AAR, for one) key
    # their R.txt under None; they need no R class of their own.
    packages = sorted([p for p in resources.packages_to_r_txts.keys() if p and p != resources.package])

    # rules_android files every aar_import's R.txt under None too: an AAR's package is only in its
    # manifest, an action output. So every library's manifest goes to link.sh as well, which reads
    # its package, and each AAR gets R classes with this APK's IDs rather than only its own
    # non-final ones (whose IDs name nothing in this APK).
    nodes = resources.direct_resources_nodes.to_list()
    transitive_nodes = getattr(resources, "transitive_resources_nodes", None)
    if transitive_nodes:
        nodes = transitive_nodes.to_list() + nodes
    library_manifests = {}
    for node in nodes:
        if getattr(node, "manifest", None) and node.label != target.label:
            library_manifests[node.manifest.path] = node.manifest
    manifests = [library_manifests[path] for path in sorted(library_manifests.keys())]

    args = ctx.actions.args()
    args.add(sdk.aapt2.executable)
    args.add(java_runtime.java_home)
    args.add(apk)
    args.add(r_jar)
    args.add(manifest)
    args.add(sdk.android_jar)
    args.add(":".join(packages + [f.path for f in manifests]))
    args.add(assets)
    args.add_all(compiled)

    ctx.actions.run_shell(
        # aapt2 is a tool from the SDK toolchain; naming the toolchain lets automatic exec
        # groups place the action (--incompatible_auto_exec_groups).
        toolchain = ANDROID_SDK_TOOLCHAIN_TYPE,
        inputs = depset(
            [manifest, sdk.android_jar, assets, ctx.file._link] + compiled + staged.values() + manifests,
            transitive = [java_runtime.files],
        ),
        tools = [sdk.aapt2],
        outputs = [apk, r_jar],
        arguments = [ctx.file._link.path, args],
        mnemonic = "ComposePreviewAndroidLink",
        progress_message = "Linking Compose preview resources for %{label}",
        command = 'script="$1"; shift; exec /bin/sh "$script" "$@"',
    )
    return struct(
        manifest = manifest,
        apk = apk,
        r_jar = r_jar,
        compile_sdk = _compile_sdk(sdk.android_jar),
        # What a link over several targets needs, so a caller can relink their union with
        # link.sh: the overlay order, the staged assets, every R package and the tools.
        inputs = struct(
            compiled = compiled,
            assets = staged,
            # A manifest path names the package it declares (link.sh's EXTRA_PACKAGES).
            packages = [resources.package] + packages + [f.path for f in manifests],
            manifests = manifests,
            aapt2 = sdk.aapt2.executable,
            aapt2_binary = _aapt2_binary(sdk),
            java_home = java_runtime.java_home,
            java_files = java_runtime.files,
            android_jar = sdk.android_jar,
        ),
    )

def _collect_impl(target, ctx):
    return [_own_jars(target, ctx), _resource_order(target, ctx)]

# Propagates along `exports`, so a kt_android_library (an android_library
# exporting the Kotlin classes) reports the Kotlin jars as its own, and along
# `deps`, to order resource overlays. JavaInfo already carries the runtime closure.
compose_preview_android_collector = aspect(
    implementation = _collect_impl,
    attr_aspects = ["deps", "exports"],
    provides = [_OwnJarsInfo, _ResourceOrderInfo],
)

ComposePreviewAndroidInfo = provider(
    doc = """An Android target's linked render inputs, for compose_preview's `module`.

Returned by compose_preview_android_module_aspect, which compose_preview attaches to its `module`
attribute; the same values the CLI's aspect writes as artifacts schema 2.""",
    fields = {
        "project_jars": "list of the target's own runtime jars (an exported library's included)",
        "runtime": "depset of every runtime jar, the final-ID R jar first",
        "sources": "list of the target's own .kt/.java sources (an exported library's included)",
        "data_files": "list of the runfiles that are not runtime jars",
        "android": "dict: the artifacts' `android` block (packageName, resourceApk, manifest, rJar, compileSdk)",
        "package": "the target's resource package; its app clock prefix",
        "compile_sdk": "int or None: the SDK platform level the link used",
        "android_jar": "File: the SDK's android.jar the link used",
        "files": "depset of the linked files (resource APK, manifest, R jar)",
    },
)

def _linked(target, ctx):
    """The selected target's linked render inputs, or None when it is not an Android library."""

    # A selected target that is not an Android library with its own classes gets no metadata
    # output, and the CLI reports that no metadata file was produced for it.
    if JavaInfo not in target or StarlarkAndroidResourcesInfo not in target:
        return None
    java = target[JavaInfo]
    project_jars = target[_OwnJarsInfo].jars.to_list()
    if not project_jars:
        return None
    resources = target[StarlarkAndroidResourcesInfo]
    linked = _link(ctx, target, resources, target[_ResourceOrderInfo])

    # The final-ID R classes must win over every library's non-final R classes.
    runtime = depset(
        [linked.r_jar] + project_jars,
        transitive = [java.transitive_runtime_jars],
        order = "preorder",
    )
    sources = target[_OwnJarsInfo].sources.to_list()
    runfiles = target[DefaultInfo].default_runfiles.files

    # dataFiles is the runfiles less the runtime jars, a set difference that Args cannot express
    # lazily: both depsets are flattened here, once, and only the filtered list outlives analysis.
    runtime_paths = {f.path: True for f in runtime.to_list()}
    data_files = [f for f in runfiles.to_list() if f.path not in runtime_paths]
    android = {
        "packageName": resources.package,
        "resourceApk": linked.apk.path,
        "manifest": linked.manifest.path,
        "rJar": linked.r_jar.path,
    }
    if linked.compile_sdk:
        android["compileSdk"] = linked.compile_sdk
    return struct(
        linked = linked,
        project_jars = project_jars,
        runtime = runtime,
        sources = sources,
        runfiles = runfiles,
        data_files = data_files,
        android = android,
        package = resources.package,
    )

def _impl(target, ctx):
    found = _linked(target, ctx)
    if not found:
        return []
    linked = found.linked
    android = dict(found.android)
    link_inputs = linked.inputs

    # Encoded eagerly: these lists were already flattened for the link action, and are bounded by
    # the resource archives and assets, not by the runtime classpath.
    android["link"] = {
        "script": ctx.file._link.path,
        "aapt2": link_inputs.aapt2_binary,
        "javaHome": link_inputs.java_home,
        "androidJar": link_inputs.android_jar.path,
        "packages": link_inputs.packages,
        "compiledResources": [f.path for f in link_inputs.compiled],
        "assets": [{"path": path, "file": f.path} for path, f in link_inputs.assets.items()],
    }
    metadata = ctx.actions.declare_file(target.label.name + ".compose-preview.json")
    ctx.actions.write(metadata, compose_preview_metadata_args(
        ctx,
        schema_version = 2,
        target = target.label,
        project_jars = found.project_jars,
        runtime = found.runtime,
        source_files = found.sources,
        data_files = found.data_files,
        extra = {"android": android},
    ))
    return [
        OutputGroupInfo(
            compose_preview_metadata = depset([metadata]),
            compose_preview_runtime = depset(
                found.sources + [linked.apk, linked.manifest, ctx.file._link, link_inputs.android_jar] +
                link_inputs.compiled + link_inputs.assets.values() + link_inputs.manifests,
                transitive = [found.runtime, found.runfiles, link_inputs.java_files],
            ),
        ),
    ]

def _module_impl(target, ctx):
    found = _linked(target, ctx)
    if not found:
        return []
    linked = found.linked
    return [ComposePreviewAndroidInfo(
        project_jars = found.project_jars,
        runtime = found.runtime,
        sources = found.sources,
        data_files = found.data_files,
        android = found.android,
        package = found.package,
        compile_sdk = linked.compile_sdk,
        android_jar = linked.inputs.android_jar,
        files = depset([linked.apk, linked.manifest, linked.r_jar]),
    )]

compose_preview_android_aspect = aspect(
    implementation = _impl,
    # Applied to the selected target only; the collector walks its dependencies.
    requires = [compose_preview_android_collector],
    attrs = {
        # The resource link, shared with the CLI's link over a whole selection. Relative to
        # this package, so the rules also load from a vendored copy below a repository root.
        "_link": attr.label(default = Label(":link.sh"), allow_single_file = True),
        # Compiles the generated R sources; the JDK java_info.bzl names, which a deployment
        # substitutes along with the providers.
        "_jdk": attr.label(default = JDK_RUNTIME, providers = [JavaRuntimeInfo], cfg = "exec"),
    },
    toolchains = [config_common.toolchain_type(ANDROID_SDK_TOOLCHAIN_TYPE, mandatory = True)],
)

# compose_preview attaches this to its `module` attribute, so an Android target renders through the
# same bundle, render and bake actions as a Desktop one. It applies only to targets advertising
# AndroidIdeInfo (android_library, and so kt_android_library's wrapper), so a Desktop module never
# resolves the Android SDK toolchain; the CLI's aspect above is applied to whatever it is named on.
compose_preview_android_module_aspect = aspect(
    implementation = _module_impl,
    requires = [compose_preview_android_collector],
    required_providers = [AndroidIdeInfo],
    attrs = {
        "_link": attr.label(default = Label(":link.sh"), allow_single_file = True),
        "_jdk": attr.label(default = JDK_RUNTIME, providers = [JavaRuntimeInfo], cfg = "exec"),
    },
    toolchains = [config_common.toolchain_type(ANDROID_SDK_TOOLCHAIN_TYPE, mandatory = True)],
    provides = [ComposePreviewAndroidInfo],
)
