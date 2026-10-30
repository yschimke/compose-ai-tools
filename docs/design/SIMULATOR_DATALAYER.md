# A paired phone and watch in the simulator: the Wearable Data Layer

Status: **prototype, behind `--pair`.** `compose-preview launch phone.apk --pair wear.apk` runs
both apps, each in its own simulator, joined by a simulated Wearable Data Layer. Home Assistant
RemoteCompose's real phone and Wear APKs exchange data items and messages through it unmodified
(evidence below).

## The problem

`compose-preview launch` ([`SIMULATOR.md`](../SIMULATOR.md)) runs one APK in one Robolectric
sandbox in one JVM. Google Play services is not there, so the Wearable API
(`com.google.android.gms.wearable`: `DataClient`, `MessageClient`, `NodeClient`,
`CapabilityClient`, and the manifest `WearableListenerService`s) reports `SERVICE_INVALID`. A
phone app and its Wear OS companion cannot see each other, so nothing that depends on the pair
(sync, a watch sending taps to the phone, a phone pushing settings to the watch) can be driven or
watched.

## Approaches considered

**(a) Substitute the Wearable clients inside each sandbox.** `Wearable.getDataClient(context)`
and its siblings are the one door every app (and Horologist's datalayer library, which takes
those clients as constructor arguments) goes through. Hand back our own subclass of the app's
`DataClient` and the binder to `com.google.android.gms` is never reached. **Chosen.**

**(b) A fake `com.google.android.gms` service speaking the wearable AIDL.** The client side
would be the app's own, unmodified, but the AIDL is unpublished and obfuscated, differs between
play-services-wearable releases, and sits behind `GoogleApiManager`'s connection, availability
and signature checks (`GoogleApiAvailability`, the CN companion check Home Assistant guards
against). Weeks of reverse engineering per version, for no behavior an app can observe that (a)
lacks. Rejected.

**(c) How the two apps meet.** Two sandboxes in one JVM share Robolectric's static state
(`RuntimeEnvironment`, the main looper, native graphics, the simulator's window loop), which is
built for one app per process. Two `launch` JVMs, unchanged, joined by a small relay, keep
everything that already works. The CLI hosts the relay. **Chosen**, combined with (a).

## Design

```
compose-preview launch phone.apk --pair wear.apk --control stdio
 ├─ DataLayerHub (CLI)            localhost socket; relays frames, says "bye" for a node that leaves
 ├─ PairedLaunch (CLI)            stdin → the "device" a command names; stdout lines tagged "device"
 ├─ simulator JVM: phone.apk      -Dcomposeai.simulator.datalayer.{hub,node=phone-sim,name}
 │    Wearable.getXClient ─► ShadowWearable ─► DataLayer ─► DataLayerLink ─┐
 └─ simulator JVM: wear.apk       qualifiers w227dp-h227dp-round-watch-xhdpi       │
      Wearable.getXClient ─► ShadowWearable ─► DataLayer ─► DataLayerLink ─┘ (hub)
```

**The seam.** `ShadowWearable` is a Robolectric shadow of
`com.google.android.gms.wearable.Wearable`, an app class (the APK's own copy), instrumented only
for a paired launch. Its static `getDataClient` / `getMessageClient` / `getNodeClient` /
`getCapabilityClient` (all overloads) return `DataLayerClients.Data` / `Messages` / `Nodes` /
`Capabilities`: subclasses of the app's own abstract clients, so `GoogleApi`'s constructor runs
as it always did, and every `Task` they return is the app's own `Tasks.forResult` or a
`TaskCompletionSource`. The shadow names no Play services type (it is also loaded outside the
sandbox, where the app's classes are not): it returns `Object`, which the instrumented caller
casts, and names `Wearable$WearableOptions` through `@ClassName`.

**Compiling against an app's library.** `:simulator-host` compiles the clients against a
`gmsStubs` source set: hand-written signatures of the slice of play-services-wearable, -base,
-basement and -tasks used, checked against 20.0.1 with `javap`. The stubs are never packaged; at
run time those classes come from the APK. Constants are copied exactly, since javac inlines them.

**The node.** `DataLayer` is what Play services' wearable service does for one app:

| API | Simulated as |
| --- | --- |
| `NodeClient.getConnectedNodes` / `getLocalNode` | the peers that said `hello` / this node (`phone-sim`, `watch-sim`, "Simulated Phone") |
| `CapabilityClient` | the app's `android_wear_capabilities` resource plus `addLocalCapability`, announced in `hello`; `getCapability` lists the peers that have it |
| `MessageClient.sendMessage` | a `message` frame; to an unknown node, `TARGET_NODE_NOT_CONNECTED` (4000) |
| `MessageClient.sendRequest` | a `message` frame with a `requestId`; the peer's `RpcService` or `REQUEST_RECEIVED` service answers with a `response` |
| `DataClient.putDataItem` | stored as `wear://<local>/<path>`, synced to the peers, and synced again to a node that connects later |
| `getDataItems` / `getDataItem` / `deleteDataItems` | over every node's items, with `*` / node hosts and literal / prefix filters |
| assets | `Asset.createFromBytes` bytes travel with the item; `getFdForAsset` streams them |

**Delivery** runs on the receiving app's main looper: the live listeners the app added (with
their URI filters), then each manifest service whose intent filter matches the action
(`MESSAGE_RECEIVED`, `REQUEST_RECEIVED`, `DATA_CHANGED`, `CAPABILITY_CHANGED`) and the
`wear://<node>/<path>` URI, as resolved by the package manager from the APK's manifest. The
service is created through `Navigator` (reported as a `service` event) and called directly:
`onMessageReceived`, `onRequest`, `onDataChanged`, `onCapabilityChanged`. `DataEventBuffer` and
`DataItemBuffer` are the app's own classes over a `DataHolder` built with the columns Play
services sends (`path`, `data`, `event_type`, `asset_key`, `asset_id`), so `DataMapItem` and
every other reader work unchanged.

**The control channel.** With `--pair`, a command with `"device":"watch"` goes to the watch;
without, to the phone. Every output line carries its `"device"`. Each crossing is a `datalayer`
event:

```json
{"device":"watch","event":"datalayer","kind":"data","direction":"in","node":"phone-sim",
 "path":"/wear/settings","bytes":43,"state":"changed",
 "deliveredTo":["…WearSyncRepository$$ExternalSyntheticLambda0","…WearDataDeletionListenerService"]}
```

and `{"cmd":"dataLayer"}` reports the node, its peers and their capabilities, every data item, and
the listener counts.

## Evidence: Home Assistant RemoteCompose

`homeassistant-debug.apk` (`:app`) with `homeassistant-wear-debug.apk` (`:wear`), both
unmodified debug builds, `--sdk 36`:

- **Peers.** Each node announced its manifest capability: the phone
  `data_layer_app_helper_device_phone`, the watch `data_layer_app_helper_device_watch`.
- **Phone → watch data item.** The phone's `MobileWearSyncManager` wrote `/wear/settings` (a
  43-byte `DataMap` with the inline proto) from `Application.onCreate()`. The watch received it
  as `wear://phone-sim/wear/settings` and delivered it both to `WearSyncRepository`'s live
  `OnDataChangedListener` and to the manifest `WearDataDeletionListenerService`, which
  `Navigator` created for it.
- **Watch → phone messages.** The watch's `WearLeaseController` sent its `/wear/lease`
  heartbeat (`nodeClient.connectedNodes` → `sendMessage`) every few seconds while
  `WearMainActivity` was in front; each reached the phone's `MobileWearSyncManager` lease
  listener.

- **The watch follows the phone.** Tapping "Offline demo · sample data on this phone" on the
  phone (`{"cmd":"tap","textContains":"Offline demo"}`) made the phone write 13 data items —
  `/wear/settings` now carrying `demo://terrazzo`, `/wear/pinned`, `/wear/sections`,
  `/wear/slot-designs`, `/wear/widget-cards`, a card design and seven `/wear/dashboard/*` items of
  3–24 KB — and send `/wear/stream` messages (one of 30 KB). All reached the watch, whose
  `WearSyncRepository` and `WearDataDeletionListenerService` took them, and its UI switched to
  the phone's demo mode:

| | Before the tap | After |
| --- | --- | --- |
| Phone | ![phone before](evidence/simulator-datalayer/phone-before.png) | ![phone after](evidence/simulator-datalayer/phone-after.png) |
| Watch | ![watch before](evidence/simulator-datalayer/watch-before.png) | ![watch after](evidence/simulator-datalayer/watch-after.png) |

One bug the pair found in the simulator rather than the app: the first version called manifest
services on the main thread, and `WearDataDeletionListenerService.onDataChanged` — written, as
its comment says, for Play services' background thread — `runBlocking`s on work that needs the
main thread, so the watch froze on the first large item. Services' callbacks now run on a
`WearableListenerService` handler thread, in order, as on a device.

## What is missing

- **Fidelity gaps, all in `DataLayer`.** `getCapability` leaves out the local node (Play
  services includes it when the local app has the capability); filters on `getCapability` /
  `getAllCapabilities` (`FILTER_REACHABLE` vs `FILTER_ALL`) are ignored, since every peer is
  reachable; `onPeerConnected` / `onConnectedNodes` are not delivered; a data item's
  `TYPE_CHANGED` is delivered even when its bytes did not change; `getNodeId` answers null.
- **`ChannelClient`** (streams between nodes) is not simulated; `getChannelClient` still returns
  Play services' real client, which fails as before.
- **The deprecated `GoogleApiClient` APIs** (`Wearable.DataApi`, `MessageApi`, …) are not
  shadowed.
- **R8-minified APKs.** A minified app renames or removes `Wearable` and the abstract client
  methods, and the shadow, which matches by name, no longer applies. Debug builds work;
  release builds would need the shadow keyed by a mapping file or a keep rule.
- **Threading.** Manifest services are called on one background thread, live listeners on the
  main thread (Play services' default without a `Looper` argument); a listener added with its own
  looper or executor is still called on the main thread.
- **Starting the other app.** Real Play services would start a stopped app to deliver to its
  manifest service; here both apps are always running, so that path is not exercised.
- **One phone, one watch.** The hub relays to every node, so more watches would work, but the
  CLI starts exactly two.
- **Driving it from the control channel.** An agent cannot yet inject a message or data item as
  if from the peer, which would let one simulator be tested against a scripted counterpart.
- **MeshCore** was not run: no Wear APK was available. Its watch calls the phone over
  Horologist's gRPC (`MessageClientChannel` → `sendRequest`, answered by the phone's
  `BaseGrpcDataService.onRequest`, both of which this prototype handles), but it targets
  `TargetNodeId.PairedPhone`, which Horologist resolves as
  `getCapability("horologist_phone").nodes.singleOrNull()`, and the phone APK declares no
  `android_wear_capabilities` at all (checked in its `resources.arsc`). On a device that
  resolves to no node too, so the simulator would surface a real MeshCore bug rather than a
  simulator gap.

## Estimate

The prototype covers what both apps use. To make it the default for `launch --pair`:

| Work | Estimate |
| --- | --- |
| Fidelity gaps above, with unit tests over a fake link | 1–2 days |
| Inject-as-peer control commands (`dataLayerSend`, `dataLayerPut`) | 0.5 day |
| `ChannelClient` (pipes over the hub) | 2 days |
| Minified APKs (mapping-file lookup of `Wearable` and the clients) | 2–3 days |
| MeshCore end to end, once its phone declares `horologist_phone` | 0.5 day |
| MCP server / `docs/SIMULATOR.md` integration of the paired session | 1 day |
