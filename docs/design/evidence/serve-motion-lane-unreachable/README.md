# The Motion lane, offered and then unreachable

Captured against the **live** `preview.coo.ee` / `m3.preview.coo.ee` deployment
(server `v1.12.0`) while diagnosing why no published capture would play. These
are the failure, not the fix — the sibling `renders/serve-motion-lane/` shots
are what the lane looks like working.

| file | what it is |
| --- | --- |
| `chip-present-switch-on.png` | `Switch On`'s control row — the **Motion** chip is offered, because the catalog declares two captures |
| `chip-absent-button-filled.png` | `Button Filled`'s row — no chip at all, because `FilledButton` carries no `@InteractionPreview` and so publishes no capture |
| `lane-open-live-failure.png` | the chip pressed: caption bar populated, `▶ Recording` lit, and the stage reading **"The recorded interaction could not be loaded."** |

The third shot is the bug this evidence exists for. The lane opened, the
caption came off the published `catalog.json`, and the bytes 404'd — so the
viewer reported a missing artifact for what were two routing faults:

- `motion` was absent from `ServeSites.RESERVED_SYSTEMS`, so the site host's
  scoping interceptor refused `/motion/…` before routing ever saw it;
- `handleMotion` peeked rather than leased, so a suspended catalog answered 404
  on the canonical path too.

Reproducing the measurement, which is what separated "GitHub is down" from a
server bug — the branch serves the bytes while the server does not:

```
curl -sI https://raw.githubusercontent.com/yschimke/m3-catalog/design-artifacts/m3-catalog/motion/switch-on/ideal__default__light.apng   # 200, 495669 bytes
curl -s -o /dev/null -w '%{http_code}\n' https://preview.coo.ee/m3-catalog/motion/switch-on__ideal__default__light.apng                 # 404, 8/8 attempts
curl -s -o /dev/null -w '%{http_code}\n' https://preview.coo.ee/m3-catalog/reference/switch-on__ideal__default__light.png               # 200
```
