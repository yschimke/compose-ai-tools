# Soft-keyboard recording

`soft-keyboard-typing.json` turns the ordinary `Soft Keyboard — typing` preview into a deterministic
input recording. It deliberately uses the same `input.keyDown` / `input.keyUp` events as a held
interactive session; the sample composable does not call the keyboard connector directly.

From the repository root:

```shell
compose-preview record \
  --module :samples:android \
  --preview "Soft Keyboard — typing" \
  --script samples/android/compose-previews/recordings/soft-keyboard-typing.json \
  --out samples/android/build/compose-previews/soft-keyboard-typing.apng
```

The final `assert.textEquals` event also makes the command fail if real text dispatch did not produce
`hello world` in the focused field.
