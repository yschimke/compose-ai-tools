# Remote Compose JSON compiler

`RemoteComposeJson.compile(source)` assembles authoring JSON into standard `.rc` bytes on a plain
JVM. Without `compilerProfile`, it uses AndroidX's unextended authoring parser. `dump(bytes)` is
operation-level inspection JSON, not an editable source round trip.

## Named integer expressions

Use the explicit top-level profile `compose-preview-integer-expressions-v1` when an integer state
value needs a computed expression, for example to select a StateLayout branch. This profile is an
extension provided by this compiler; it is not accepted by AndroidX's unextended JSON parser.
An unknown profile fails compilation. The profile name describes the source compiler, separately
from the Remote Compose API level and binary `profiles` feature mask in the document header.

```json
{
  "compilerProfile": "compose-preview-integer-expressions-v1",
  "header": { "width": 100, "height": 100 },
  "root": [
    { "type": "resources", "integers": { "page": { "value": 10, "export": true } } },
    {
      "type": "box",
      "modifiers": ["fillMaxSize"],
      "children": [
        { "type": "integerExpression", "name": "index", "value": "clamp(@page / 10 - 1, 0, 1)" },
        {
          "type": "stateLayout",
          "indexId": "@index",
          "children": [
            { "type": "box", "modifiers": ["fillMaxSize", { "background": "#FFFF0000" }] },
            { "type": "box", "modifiers": ["fillMaxSize", { "background": "#FF00FF00" }] }
          ]
        }
      ]
    }
  ]
}
```

Each `integerExpression` declares a document-wide integer name with a `value` expression. Names
use letters, digits and underscores, starting with a letter or underscore. References use `@name`
and must refer to previously declared integers. Forward references, duplicate names and float
references fail compilation. Expressions support signed 32-bit literals, parentheses, `+ - * / %`,
unary minus, `abs`, `min`, `max` and `clamp`, using AndroidX's integer expression compiler and normal
32-bit arithmetic. They are not promoted to floats. Division by zero and integer overflow retain
the runtime's arithmetic behavior; the compiler does not promise arbitrary-precision arithmetic.

A declaration must fit the wire operation's 32 operand/operator slots. Split larger expressions
into ordered named declarations. Declarations only accept `type`, `name` and `value`; they do not
draw a component or accept modifiers. Put expressions inside a layout-bearing Box before the
StateLayout or other content that reads them, so the player's normal paint path evaluates updates.
Inactive-branch evaluation and transitions follow the player's behavior.

The adapter registers a parser extension and emits ordinary `IntegerExpression` operations. It
does not replace AndroidX parser classes, convert integer state to floats, or patch binary bytes.
Its internal package placement allows access to the expression compiler and symbol tables exposed
with package visibility in AndroidX alpha18/19. Dependency upgrades must run the profile tests.
Player support remains a separate requirement: compiling successfully is not a rendering claim.

Run `./gradlew :remotecompose-json:test :remotecompose-json:checkKotlinAbi`.
