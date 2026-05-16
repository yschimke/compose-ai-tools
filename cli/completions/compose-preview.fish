# Fish completions for compose-preview.
#
# Install:
#   cp compose-preview.fish ~/.config/fish/completions/
#   # or, system-wide:
#   sudo cp compose-preview.fish /usr/share/fish/vendor_completions.d/

function __compose_preview_needs_command
    set -l cmd (commandline -opc)
    set -l valued_flags --module --filter --id --output --timeout --plugin-version \
        --fail-on --desc --branch --remote --pr-number --message \
        --with-extension --with --project --antigravity-config --codex-config \
        --replicas-per-daemon
    set -l i 2
    while test $i -le (count $cmd)
        set -l arg $cmd[$i]
        if contains -- $arg $valued_flags
            set i (math $i + 2)
            continue
        end
        switch $arg
            case '-*'
                set i (math $i + 1)
                continue
            case '*'
                return 1
        end
    end
    return 0
end

function __compose_preview_command
    set -l cmd (commandline -opc)
    set -l valued_flags --module --filter --id --output --timeout --plugin-version \
        --fail-on --desc --branch --remote --pr-number --message \
        --with-extension --with --project --antigravity-config --codex-config \
        --replicas-per-daemon
    set -l i 2
    while test $i -le (count $cmd)
        set -l arg $cmd[$i]
        if contains -- $arg $valued_flags
            set i (math $i + 2)
            continue
        end
        switch $arg
            case '-*'
                set i (math $i + 1)
                continue
            case '*'
                echo $arg
                return 0
        end
    end
    return 1
end

function __compose_preview_subcommand
    set -l top (__compose_preview_command)
    test -z "$top"; and return 1
    set -l cmd (commandline -opc)
    set -l found 0
    for arg in $cmd[2..-1]
        if test $found -eq 1
            switch $arg
                case '-*'
                    continue
                case '*'
                    echo $arg
                    return 0
            end
        end
        if test "$arg" = "$top"
            set found 1
        end
    end
    return 1
end

function __compose_preview_using_command
    set -l want $argv[1]
    set -l have (__compose_preview_command)
    test "$have" = "$want"
end

function __compose_preview_using_mcp_sub
    __compose_preview_using_command mcp; or return 1
    set -l want $argv[1]
    set -l have (__compose_preview_subcommand)
    test "$have" = "$want"
end

function __compose_preview_using_extensions_sub
    __compose_preview_using_command extensions; or return 1
    set -l want $argv[1]
    set -l have (__compose_preview_subcommand)
    test "$have" = "$want"
end

function __compose_preview_using_bundle_sub
    __compose_preview_using_command bundle; or return 1
    set -l want $argv[1]
    set -l have (__compose_preview_subcommand)
    test "$have" = "$want"
end

# Top-level subcommands.
complete -c compose-preview -f -n __compose_preview_needs_command -a show \
    -d 'Render previews; print id, path, sha256, changed'
complete -c compose-preview -f -n __compose_preview_needs_command -a show-resources \
    -d 'Render Android XML resource previews'
complete -c compose-preview -f -n __compose_preview_needs_command -a list \
    -d 'List discovered previews'
complete -c compose-preview -f -n __compose_preview_needs_command -a render \
    -d 'Render previews; --output copies a single match'
complete -c compose-preview -f -n __compose_preview_needs_command -a a11y \
    -d 'Render with a11y extension; print ATF findings'
complete -c compose-preview -f -n __compose_preview_needs_command -a extensions \
    -d 'Introspect registered data extensions'
complete -c compose-preview -f -n __compose_preview_needs_command -a profile \
    -d 'Run a saved JSON profile'
complete -c compose-preview -f -n __compose_preview_needs_command -a doctor \
    -d 'Verify Java 17 + Compose/AGP environment'
complete -c compose-preview -f -n __compose_preview_needs_command -a devices \
    -d 'List known @Preview(device=...) ids'
complete -c compose-preview -f -n __compose_preview_needs_command -a share-gist \
    -d 'Create a gist from markdown + images'
complete -c compose-preview -f -n __compose_preview_needs_command -a publish-images \
    -d 'Push rendered PNGs to a shared branch'
complete -c compose-preview -f -n __compose_preview_needs_command -a bundle \
    -d 'Pack / inspect / render portable preview bundles (PNG+ZIP polyglot)'
complete -c compose-preview -f -n __compose_preview_needs_command -a mcp \
    -d 'MCP server lifecycle (serve|install|doctor)'
complete -c compose-preview -f -n __compose_preview_needs_command -a update \
    -d 'Re-run bootstrap installer to pull latest release'
complete -c compose-preview -f -n __compose_preview_needs_command -a version \
    -d 'Print installed bundle version'
complete -c compose-preview -f -n __compose_preview_needs_command -a help \
    -d 'Show help'

# Top-level global flags (work before a subcommand is chosen too).
complete -c compose-preview -f -n __compose_preview_needs_command -l version -s V \
    -d 'Print version and exit'
complete -c compose-preview -f -n __compose_preview_needs_command -l help -s h \
    -d 'Show help'

# Shared command-base flags — apply to every render-driving subcommand.
set -l __cp_render_cmds show show-resources list render a11y

for sub in $__cp_render_cmds
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l module -d 'Target Gradle module path (default: all)'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l filter -d 'Case-insensitive substring match on preview id'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l id -d 'Exact match on preview id'
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l progress -d 'Print per-task progress to stderr'
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l verbose -s v -d 'Show full Gradle build output'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l timeout -d 'Gradle build timeout in seconds (default 300)'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l with-extension -a 'a11y' -d 'Enable a data extension for this run'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l with -a 'a11y' -d 'Alias for --with-extension'
    complete -c compose-preview -x -n "__compose_preview_using_command $sub" \
        -l force -d 'Re-run all tasks (escape hatch; requires reason)'
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l no-auto-inject -d 'Skip auto-injecting the preview plugin via init script'
end

# JSON-emitting commands: --json, --brief.
for sub in show show-resources list a11y
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l json -d 'Emit JSON envelope'
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l brief -d 'JSON only: drop functionName/className/sourceFile/params'
end

# Commands that honour --changed-only.
for sub in show show-resources a11y
    complete -c compose-preview -f -n "__compose_preview_using_command $sub" \
        -l changed-only -d 'Drop previews with no changed capture'
end

# render: --output <path>.
complete -c compose-preview -r -n '__compose_preview_using_command render' \
    -l output -d 'Copy the single matched PNG to this path'

# a11y / report commands: --fail-on.
complete -c compose-preview -x -n '__compose_preview_using_command a11y' \
    -l fail-on -a 'errors warnings none' \
    -d 'Exit non-zero on errors/warnings (default: mirror Gradle)'

# extensions subcommand.
complete -c compose-preview -f -n '__compose_preview_using_command extensions; and not __compose_preview_subcommand' \
    -a 'list help' -d 'extensions subcommand'
complete -c compose-preview -f -n '__compose_preview_using_extensions_sub list' \
    -l json -d 'Emit JSON envelope'

# profile: positional path to JSON.
complete -c compose-preview -F -n '__compose_preview_using_command profile' \
    -d 'Profile JSON file'

# doctor.
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l json -d 'Emit JSON envelope'
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l report -d 'Emit markdown-style report'
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l explain -d 'Print remediation guidance for failed checks'
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l verbose -s v -d 'Verbose output'
complete -c compose-preview -r -n '__compose_preview_using_command doctor' \
    -l project -d 'Project root (default: cwd)'
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l daemon -d 'Also smoke-test each module daemon (slow)'
complete -c compose-preview -f -n '__compose_preview_using_command doctor' \
    -l with-daemon -d 'Alias for --daemon'
complete -c compose-preview -x -n '__compose_preview_using_command doctor' \
    -l plugin-version -d 'Plugin version to recommend in remediation hints'

# devices.
complete -c compose-preview -f -n '__compose_preview_using_command devices' \
    -l json -d 'Emit JSON envelope'
complete -c compose-preview -f -n '__compose_preview_using_command devices' \
    -l help -s h -d 'Show devices help'

# share-gist.
complete -c compose-preview -f -n '__compose_preview_using_command share-gist' \
    -l json -d 'Emit JSON envelope'
complete -c compose-preview -f -n '__compose_preview_using_command share-gist' \
    -l public -d 'Make the gist public'
complete -c compose-preview -f -n '__compose_preview_using_command share-gist' \
    -l secret -d 'Make the gist secret (default)'
complete -c compose-preview -x -n '__compose_preview_using_command share-gist' \
    -l desc -d 'Gist description'

# publish-images.
complete -c compose-preview -F -n '__compose_preview_using_command publish-images' \
    -d 'Directory of rendered PNGs'
complete -c compose-preview -x -n '__compose_preview_using_command publish-images' \
    -l branch -d 'Target branch (default compose-preview/pr)'
complete -c compose-preview -x -n '__compose_preview_using_command publish-images' \
    -l remote -d 'Git remote (default origin)'
complete -c compose-preview -x -n '__compose_preview_using_command publish-images' \
    -l pr-number -d 'Pull request number for commit message'
complete -c compose-preview -x -n '__compose_preview_using_command publish-images' \
    -l message -d 'Custom commit message'
complete -c compose-preview -f -n '__compose_preview_using_command publish-images' \
    -l allow-non-preview-branch -d 'Allow pushing to non-compose-preview/ branches'
complete -c compose-preview -f -n '__compose_preview_using_command publish-images' \
    -l json -d 'Emit JSON envelope'

# bundle subcommands.
complete -c compose-preview -f -n '__compose_preview_using_command bundle; and not __compose_preview_subcommand' \
    -a 'pack inspect extract render help' -d 'bundle subcommand'

# bundle pack.
complete -c compose-preview -x -n '__compose_preview_using_bundle_sub pack' \
    -l module -d 'Target Gradle module (e.g. :samples:cmp)'
complete -c compose-preview -x -n '__compose_preview_using_bundle_sub pack' \
    -l id -d 'Preview id to include (repeatable; first is cover)'
complete -c compose-preview -F -n '__compose_preview_using_bundle_sub pack' \
    -l output -s o -d 'Output .png polyglot path'
complete -c compose-preview -f -n '__compose_preview_using_bundle_sub pack' \
    -l no-render -d 'Skip renderPreviews; pack with a stub gray cover'

# bundle inspect / extract / render — positional <bundle.png> file argument.
for sub in inspect extract render
    complete -c compose-preview -F -n "__compose_preview_using_bundle_sub $sub" \
        -d 'Bundle file (.png polyglot)'
end

# bundle extract / render — output dir.
for sub in extract render
    complete -c compose-preview -F -n "__compose_preview_using_bundle_sub $sub" \
        -l output -s o -d 'Output directory'
end

# mcp subcommands.
complete -c compose-preview -f -n '__compose_preview_using_command mcp; and not __compose_preview_subcommand' \
    -a 'serve install doctor help' -d 'mcp subcommand'

# mcp serve.
complete -c compose-preview -r -n '__compose_preview_using_mcp_sub serve' \
    -l project -d 'Project root (path[:rootName])'

# mcp install / doctor common flags.
for sub in install doctor
    complete -c compose-preview -r -n "__compose_preview_using_mcp_sub $sub" \
        -l project -d 'Project root containing settings.gradle[.kts]'
    complete -c compose-preview -x -n "__compose_preview_using_mcp_sub $sub" \
        -l module -d 'Limit to a Gradle module path (repeatable)'
    complete -c compose-preview -f -n "__compose_preview_using_mcp_sub $sub" \
        -l json -d 'Emit JSON envelope'
end

# mcp install: agent host registration.
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l claude -d 'Register with Claude Code (`claude mcp add`)'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l no-claude -d 'Skip Claude Code registration'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l codex -d 'Merge into Codex config.toml'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l no-codex -d 'Skip Codex registration'
complete -c compose-preview -r -n '__compose_preview_using_mcp_sub install' \
    -l codex-config -d 'Override Codex config path'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l antigravity -d 'Merge into Antigravity mcp_config.json'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l no-antigravity -d 'Skip Antigravity registration'
complete -c compose-preview -r -n '__compose_preview_using_mcp_sub install' \
    -l antigravity-config -d 'Override Antigravity config path'
complete -c compose-preview -f -n '__compose_preview_using_mcp_sub install' \
    -l verbose -s v -d 'Verbose Gradle output'

# update.
complete -c compose-preview -f -n '__compose_preview_using_command update' \
    -l dry-run -d 'Print actions without running the installer'
