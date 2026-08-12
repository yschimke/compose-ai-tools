# CMP Desktop renders: skiko's native dependencies

Everything here is about one error line:

```
UnsatisfiedLinkError: /root/.skiko/<hash>/libskiko-linux-x64.so:
    libGL.so.1: cannot open shared object file: No such file or directory
```

It fails **every** preview in a CMP Desktop module at once, and it almost never means what it says
("install libGL"). This page is the checklist; `compose-preview doctor` automates it as the
`env.desktop-natives` check.

## What skiko actually needs

The Compose Desktop renderer draws through Skia, which the JVM extracts to `~/.skiko/<hash>/` on
first use. That `.so` has four direct `DT_NEEDED` entries beyond libc:

| soname | why |
|---|---|
| `libGL.so.1` | skiko links OpenGL even when rendering offscreen |
| `libX11.so.6` | pulled in by skiko's AWT integration |
| `libfontconfig.so.1` | font enumeration (brings freetype transitively) |
| `libstdc++.so.6` | the C++ runtime Skia is built against |

Verify against the binary you actually have, rather than trusting this table after a CMP bump:

```sh
readelf -d ~/.skiko/*/libskiko-linux-x64.so | grep NEEDED
```

On Debian/Ubuntu those four come from `libgl1 libx11-6 libfontconfig1 libstdc++6`. The Android
(Robolectric) render path needs none of this — the trap is Desktop-only.

## Four ways this breaks, in order of how often

### 1. The render JVM's loader ignores the system library path

**This is the one that wastes an afternoon,** because every diagnostic you'd reach for says the
library is fine:

```sh
$ ldconfig -p | grep libGL.so.1
        libGL.so.1 (libc6,x86-64) => /lib/x86_64-linux-gnu/libGL.so.1     # present
$ ldd ~/.skiko/*/libskiko-linux-x64.so | grep libGL
        libGL.so.1 => /lib/x86_64-linux-gnu/libGL.so.1                    # resolves
$ ./gradlew :app:composePreviewRenderAll
        libGL.so.1: cannot open shared object file                        # still fails
```

`ldd` and `ldconfig` run the **system** loader. A JDK installed from a Nix or Guix store is
patchelf'd to that store's own `ld-linux`, which does not read `/etc/ld.so.cache` and does not
search `/usr/lib/<triple>`. The two processes are not using the same loader, so they don't agree.

Check which JDK the render will fork into:

```sh
readlink -f "$(command -v java)"     # /nix/store/…-temurin-bin-17.0.19/bin/java  → store loader
```

Fix it either way round — hand the store JDK an explicit search path:

```sh
export LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
```

…or render on a JDK outside the store (`/usr/lib/jvm/…`), whose loader finds the system libs with no
environment variable at all.

### 2. `LD_LIBRARY_PATH` is set but never exported

The render is a subprocess of the Gradle daemon, which is a subprocess of the Gradle client. A shell
variable that was assigned but not exported reaches none of them, and `echo $LD_LIBRARY_PATH` will
still print it — the variable exists, it's just not in the environment.

```sh
echo "$LD_LIBRARY_PATH"          # prints a value       ← proves nothing
env | grep ^LD_LIBRARY_PATH=     # no output            ← this is what the render sees
```

Same trap with a daemon that outlived the change: a Gradle daemon started **before** the variable was
exported keeps its original environment, and so do the render subprocesses it forks. After changing
anything about the environment:

```sh
./gradlew --stop
```

### 3. The libraries genuinely aren't installed

```sh
apt-get install -y libgl1 libx11-6 libfontconfig1 libstdc++6
```

### 4. Store libraries loaded into a system-glibc JVM (hybrid sandboxes)

The mirror image of #1, and it looks nothing like a missing library — the libraries are all
*present*, and the render still fails on every preview
([#3690](https://github.com/yschimke/compose-ai-tools/issues/3690)):

```
UnsatisfiedLinkError: …/libskiko-linux-x64.so:
  /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_ABI_DT_X86_64_PLT' not found
  (required by /nix/store/…-glibc-2.42-67/lib/libpthread.so.0)
```

A Nix/Guix store ships its own glibc, and every library in it carries a `RUNPATH` back to that
glibc. So a store `libGL.so.1` reached through `LD_LIBRARY_PATH` pulls the store's `libpthread` into
a process that already holds the system `libc.so.6` — and if the store's glibc is newer, the loader
refuses the mix. This happens on hybrid userlands (an Ubuntu base with a Nix-provisioned toolchain)
whenever the render JVM is **not** the store JVM the store libraries were provisioned for: typically
a `jvmToolchain(21)` resolving to `/usr/lib/jvm/java-21-openjdk-amd64` while the daemon runs a store
JDK 17 and exports its store search path to every child.

The first preview to touch Skia reports the error above; every later one in the same JVM reports
only `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Surface`. Look at the
first failure, or read the `diagnosis` field the renderer now writes into each
`<preview>.png.error.json` — it names the mismatch, and its `runtime` block records the JVM that ran
the render and the `LD_LIBRARY_PATH` it inherited, which is the pair of facts this is otherwise
impossible to confirm from the outside.

**The plugin handles this itself.** `RenderPreviewsTask` prunes package-store directories from
`LD_LIBRARY_PATH` when the render JVM is not itself from a store, for both the pooled worker and the
per-capture fork (`RenderNativeEnv`). A store render JVM keeps everything it inherited — there,
`LD_LIBRARY_PATH` is the only channel its loader reads, which is the fix in #1. Opt out with:

```sh
./gradlew :app:composePreviewRenderAll -Dcomposeai.render.nativeEnv=inherit
```

If you hit it outside the plugin (a bare `java -jar`, an IDE, another build tool), the rule is the
same: give store libraries to a store JDK, or give a system JDK nothing and let `/etc/ld.so.cache`
do its job. `compose-preview doctor` reports the mixed state as a warning on `env.desktop-natives`.

## After fixing it, force the re-render

A failed render still produces task outputs (the `.error.json` sidecars), so `composePreviewRender`
goes **UP-TO-DATE** on the next invocation and re-reports the same stale failure — a fixed
environment can look unfixed:

```sh
./gradlew :<module>:composePreviewRender --rerun :<module>:composePreviewRenderAll
```

## Claude Code / agent cloud sessions

Cloud sandboxes hit causes 1 and 2 together, because the session-start script that provisions the GL
libraries typically installs them under a store path and exports `LD_LIBRARY_PATH` for the *agent's*
shell — which is a different thing from the environment a Gradle daemon inherits.

Checklist for a session that needs CMP Desktop renders:

1. **Provision the libs in the session-start hook**, not by hand mid-session — a fresh container
   starts from the image every time.
2. **Export `LD_LIBRARY_PATH` into the environment**, and confirm with
   `env | grep ^LD_LIBRARY_PATH=` rather than `echo`.
3. **`./gradlew --stop` once** after the hook has run, so no daemon is carrying the pre-hook
   environment.
4. **Run `compose-preview doctor`** before the first render. On a project with a CMP module it emits
   `env.desktop-natives`, which resolves each soname the way the render JVM's loader would — store
   JDK included — rather than the way `ldd` would.
5. If a render already failed, add `--rerun` (see above) so you're testing the fix and not a cached
   failure.

A network allowlist that can't reach the package/store host fails at step 1 with a completely
different error; see
[`agent-cloud.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md)
for the host list.

## CI

GitHub-hosted runners install the libraries as ordinary system packages and run a stock JDK, so the
loader finds everything and none of the above applies — which is why this failure mode shows up in
sandboxes and on developer machines with store-managed toolchains, but never in CI. Don't take a
green CI render as evidence that a local environment is configured.
