# Parameter knobs — render evidence

`parameter-knob-list.png` is the default render of
`samples/cmp/.../ParameterKnobPreviews.kt#ParameterKnobListPreview`, the same editable list as
`OverridablePreviews.kt#OverridableListPreview` with its knobs moved from `previewOverride*` calls
in the body to the preview function's own defaulted value parameters.

The two renders are **byte-identical** — one PNG serves for both, which is the point:

```
$ md5sum build/compose-previews/renders/OverridableListPreview_*.png \
         build/compose-previews/renders/ParameterKnobListPreview_*.png
03c515780db661f533e0325178c65bb1  …/OverridableListPreview_Overridable_List-c264a81f.png
03c515780db661f533e0325178c65bb1  …/ParameterKnobListPreview_Parameter_Knob_List-de590fad.png
```

Moving a knob out of the body and into the signature changes what a tool can *say* about the
preview — the knobs are now in `previews.json` without rendering anything — and changes nothing
about what it draws.
