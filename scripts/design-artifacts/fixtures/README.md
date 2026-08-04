# `fixtures/` — Remote Compose comparison policy

The production comparison replays every `ir/*.rc` sidecar from the generated remote-m3 bundle
through CMP/Wasm. This directory contains only reviewed policy inputs for that corpus.

- `remote-m3-cmp-wasm-pixel-tolerances.json` records the narrowly reviewed exceptions to the strict
  1% pixel-parity default. Entries are stale-failing and must include a rationale.
