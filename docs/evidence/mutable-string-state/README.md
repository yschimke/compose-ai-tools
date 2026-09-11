# Independent text-state player proof

`mutable-strings.json` uses the explicit state compiler profile and the ordinary Remote Compose
text layout and string-change action. Two named variables and a text literal initially contain
`Ready`. After clicking the first text and applying a named host update to the second, the real
CMP player shows `Changed`, `Review`, `Ready`. The equal literal is unchanged.

The before/after images capture two interaction states of the fixed profile. The compiler unit
test separately verifies that all three equal initial values own distinct IDs. The unextended
parser's string interning remains unchanged.

The runnable consumer test is `MutableStringJsonTest` in compose-preview-server's
`experiments/remote-compose-poc`, developed with that repository's normal local-publication
manifest. It also compiles six exact production-exporter fixtures and verifies repeated ordered
assignments, Unicode, empty text, reference-like literals and reserved-name collisions through
the actual player runtime. No compiler release is required to run the consumer proof.

Compiler validation: `./gradlew :remotecompose-json:check` runs 31 JVM tests, formatting, ABI and
the module boundary checks. The state profile does not implement String equality or selection.
