# Playground preview dimensions

The same generated `@Preview(widthDp = 360, heightDp = 360)` was compiled and rendered through
compose-preview-server's real HTTP native-preview route and Android daemon 3.2.0. The document
selects a green Remote StateLayout branch inside 24 dp padding.

Before this change the synthesized preview manifest contained only the method id. The daemon
produced its default 800 × 1488 phone image. With the local render-host build, it receives the
annotation dimensions and produces 720 × 720 pixels at its default 2× density. HTTP and MCP returned
identical PNG bytes. These are actual renders, not mockups.

| Before | After |
| --- | --- |
| ![Default phone frame](before.png) | ![Authored square frame](after.png) |

The remaining extra bottom space is a separate Android host measurement problem under investigation
in the consumer's native proof. This change fixes the preview manifest, not that content inset.

The bytecode test covers fixed rectangular dimensions, a width-only preview, unspecified dimensions,
and exclusion of unselected previews. A throwing class initializer verifies that discovery does not
execute the snippet. All 466 render-host tests and its Kotlin ABI check pass.
