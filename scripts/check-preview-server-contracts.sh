#!/usr/bin/env bash
# Build the `preview-server` build against the contract modules AS PUBLISHED ARTIFACTS.
#
# `preview-server/` is a separate Gradle build and is deliberately NOT included in the root
# `settings.gradle.kts` — see the comment at the top of preview-server/settings.gradle.kts. That
# means nothing wires the two together, and this script is the wiring: publish the contracts to
# Maven Local under a fixed probe version, then build `preview-server` against them.
#
# It is the same shape as the split itself. If a contract is unpublishable, or drags something a
# server has no business carrying, this fails — in a PR, months before anyone tries the extraction.
#
#   scripts/check-preview-server-contracts.sh          # publish + check
#   scripts/check-preview-server-contracts.sh --skip-publish
#
# Run by CI (`preview-server-contracts` in ci.yml) on PRs that touch a contract module, the serve
# package, or this build.

set -euo pipefail

cd "$(dirname "$0")/.."

# Fixed on purpose. The exchange must not depend on where release-please left the repo version, and
# a constant keeps the published probe artifacts trivially identifiable in ~/.m2 (and trivially
# distinguishable from a real release someone has installed locally). `-SNAPSHOT` because
# `ComposeAiMavenPublishingPlugin` signs every non-snapshot publication, and a local probe has no
# signatory.
PROBE_VERSION="0.0.0-contract-probe-SNAPSHOT"

# The contracts this repository still BUILDS, and so can publish under the probe version.
#
# Four others — daemon-protocol, daemon-devices, daemon-bta and agent-grant-protocol — are not here
# any more: they moved to yschimke/compose-preview-contracts and resolve from Maven Central. The
# four data-*-core modules and common-io went there too and came BACK at that repo's 2.1.0
# narrowing; they are built here again, so they belong in this list. They are still contracts, and
# `contracts` in preview-server/contract-probe/build.gradle.kts still lists them; they just are not
# ours to publish. `checkContractSurface` fails if the two lists disagree.
CONTRACT_PROJECTS=(
  ":common-io"
  ":data-render-core"
  ":data-layoutinspector-core"
  ":data-theme-core"
  ":data-preview-overrides-core"
  ":daemon:core"
  ":daemon-client"
  ":preview-data-api"
  ":render-session-api"
  ":render-session-subprocess"
  ":common-image-crop"
  ":common-web-escaping"
  ":data-remotecompose-core"
  ":data-pseudolocale-core"
  ":bundle-format"
  ":bundle-coordinates"
)

# Recorded leaks, published only so the probe can resolve at all. Every name here is a module an
# extracted preview server would be forced to depend on today and should not — see the "Recorded
# leaks" table in docs/design/PREVIEW_SERVER_SPLIT.md. They are listed separately from the contracts
# so this file says out loud what `checkContractSurface` enforces: contracts stay, leaks go.
#
# EMPTY as of #3824 preparation item 3. The last entry was `:mcp`, reached through
# `:render-session-subprocess`'s transport; those types now live in `:daemon-client`, which is a
# contract above rather than a leak here.
#
# Note this list is what gets PUBLISHED to Maven Local alongside the contracts, which is why an
# emptied entry matters beyond bookkeeping: while `:mcp` was still listed, the probe resolved
# against a copy this script had just installed, so it could have reported the leak gone while a
# real edge survived. Verify a removal with the artifact deleted from ~/.m2 first.
LEAK_PROJECTS=()

skip_publish=0
for arg in "$@"; do
  case "$arg" in
    --skip-publish) skip_publish=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [[ "$skip_publish" == "0" ]]; then
  publish_tasks=()
  for project in "${CONTRACT_PROJECTS[@]}" "${LEAK_PROJECTS[@]}"; do
    publish_tasks+=("${project}:publishToMavenLocal")
  done
  echo "==> publishing ${#CONTRACT_PROJECTS[@]} contract module(s) + ${#LEAK_PROJECTS[@]} recorded leak(s) at ${PROBE_VERSION}"
  # PLUGIN_VERSION is what ComposeAiMavenPublishingPlugin reads for the publication version.
  PLUGIN_VERSION="$PROBE_VERSION" ./gradlew --console=plain "${publish_tasks[@]}"
fi

# The nine contracts that moved out resolve at their released version, not the probe version. Read
# it from the version catalog so the pin has exactly one home: the catalog is what the main build
# compiles against, and a second copy here could disagree with it silently.
EXTERNAL_CONTRACTS_VERSION="$(
  sed -n 's/^composeai-contracts = "\(.*\)"$/\1/p' gradle/libs.versions.toml
)"
if [[ -z "$EXTERNAL_CONTRACTS_VERSION" ]]; then
  echo "could not read composeai-contracts from gradle/libs.versions.toml" >&2
  exit 1
fi

echo "==> building preview-server against the published contracts"
echo "    local contracts at $PROBE_VERSION, external contracts at $EXTERNAL_CONTRACTS_VERSION"
./gradlew --console=plain -p preview-server \
  -Pcomposeai.contractVersion="$PROBE_VERSION" \
  -Pcomposeai.externalContractsVersion="$EXTERNAL_CONTRACTS_VERSION" \
  check ktfmtCheck
