# scripts/design-artifacts

The design-artifacts export driver is no longer here. Its source is design-parity's
[`packages/export-driver`](https://github.com/yschimke/design-parity/tree/main/packages/export-driver),
published as `@design-parity/export-driver`. Change the driver there, with its tests.

This repository's workflows install it from the lock in
[`.github/design-artifacts-driver/`](../../.github/design-artifacts-driver/) with
[`.github/scripts/install-export-driver.sh`](../../.github/scripts/install-export-driver.sh), which
exports the installed directory as `DRIVER_DIR`. Renovate moves the lock when design-parity
releases.

What stays here is what this repository itself reads:

| File | Why it is here |
| --- | --- |
| `catalog.spec.schema.json`, `ui-builder.policy.schema.json` | Served from this repository's `main` at the `$schema` URLs catalog specs name, and read by `UiBuilderPolicySchemaTest`. They are copies of the driver's; `driver-contract.test.mjs` fails if they differ, so a schema change is made in design-parity and copied here in the PR that bumps the lock. |
| `scope-systems.sh`, `scope-step.sh`, `published-source.sh` and their `test-*.sh` | Decide which of this repository's own catalogs a push to `main` regenerates, for `design-artifacts.yml`. They know this repository's module layout, so they are not part of the driver. |
| `driver-contract.test.mjs` | Checks that the installed driver agrees with this repository: its workflow, its Kotlin, its sample specs and fonts, the lock, and the two schemas above. |

To run the contract checks locally:

```sh
DRIVER_DIR=$(.github/scripts/install-export-driver.sh "$(mktemp -d)") \
  node --test scripts/design-artifacts/driver-contract.test.mjs
```
