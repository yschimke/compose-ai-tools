# APNG display copy

Evidence for the preview-diff change that publishes `<name>.apng.png` beside every `.apng` on a
render branch. `motion-sample.apng` and `motion-sample.apng.png` are byte-identical; GitHub's raw host
serves the first as `application/octet-stream` (with `nosniff`) and the second as `image/png`, which
is the only difference between them.
