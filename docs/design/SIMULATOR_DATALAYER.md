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
| `NodeClient.getCompanionPackageForNode` | `com.google.android.wearable.app`, the Wear OS app, as on a device (not the peer app's package) |
| `GoogleApiAvailability.checkApiAvailability` | available, when every API is a simulated Wearable client (`ShadowGoogleApiAvailability`); Horologist's `isAvailable()` asks it |
| `RemoteActivityHelper.startRemoteActivity` | its `REMOTE_INTENT` broadcast to the Wear OS app is taken here and sent as an `activity` frame; the other node starts the intent (or, for `http(s)` / `market:` with no app activity, answers OK as its browser or Play Store would) and its `activityResult` answers the app's `ResultReceiver` |
| `RemoteAuthClient.sendAuthorizationRequest` (watch) | the bind to the Wear OS app's OAuth service connects (`WearCompanion`); the request reaches the phone as an `oauth` event, and `{"cmd":"dataLayer","oauthResponse":"<redirect URL>"}` on the phone stands in for the browser's redirect, which reaches the app's callback |
| `BridgingManager.setConfig` (watch) | the bind connects; the config is reported as a `bridging` event |
| `PhoneTypeHelper.getPhoneDeviceType` (watch) | `DEVICE_TYPE_ANDROID`, from `Settings.Global` `paired_device_os_type` |
| `PackageManager.hasSystemFeature("android.hardware.type.watch")` | true on a watch display, paired or not |
| `com.google.wear.Sdk` (watch, API 34+) | the simulator's stand-in for the Wear OS system library, `WEAR_SDK_INT` 0, so androidx.wear takes its pre-Wear-SDK paths |

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

## Evidence: every Wear interaction API, from two probe apps

Two throwaway probe apps (not committed): a phone and a Wear app with one package, Horologist
0.8.3-alpha's `PhoneDataLayerAppHelper` / `WearDataLayerAppHelper`, androidx.wear
`wear-remote-interactions` 1.1.0 and `wear-phone-interactions` 1.1.1, one button per API, run
paired on `--sdk 35`. Each API in both directions where it has two:

| API | Result |
| --- | --- |
| `NodeClient` `getLocalNode` / `getConnectedNodes` | works |
| `CapabilityClient` `getAllCapabilities` (`FILTER_ALL`, `FILTER_REACHABLE`), `addLocalCapability`, `onCapabilityChanged` | works |
| `GoogleApiAvailability.checkApiAvailability`, Horologist `isAvailable()` | works (was `SERVICE_INVALID`) |
| Horologist `connectedNodes()`, `connectedAndInstalledNodes` | works: the peer, `Installed` |
| Horologist `startRemoteOwnApp`, `startRemoteActivity(ActivityConfig)` | works, `APP_HELPER_RESULT_SUCCESS`; the activity starts on the other device |
| Horologist `installOnNode` | `SUCCESS`: the Play Store link is answered as the store would |
| Horologist `startCompanion` | `NO_COMPANION_FOUND`: there is no Wear OS app |
| `MessageClient` `sendMessage` (`/request-network`), `sendRequest` / `onRequest` | works |
| `DataClient` `putDataItem`, `getDataItems(wear://*/…, FILTER_PREFIX)`, listeners and services | works |
| `RemoteActivityHelper.startRemoteActivity` (`VIEW` + `BROWSABLE`, data, package) | works both ways (the future never completed before: see below) |
| `RemoteActivityHelper.availabilityStatus` | `STATUS_UNKNOWN` |
| `PhoneTypeHelper.getPhoneDeviceType` | `DEVICE_TYPE_ANDROID` |
| `RemoteAuthClient.sendAuthorizationRequest` | works, with `oauthResponse` as the redirect |
| `BridgingManager.setConfig` | works, reported (it looped forever before: see below) |
| `ConfirmationActivity` `OPEN_ON_PHONE_ANIMATION` | works, an ordinary activity of the app |
| `WatchFaceConfigIntentHelper` | works (pure intent extras) |
| `ChannelClient.openChannel` | fails, `API_UNAVAILABLE` (not simulated) |

| Phone → watch | Watch → phone |
| --- | --- |
| ![VIEW probe://target/from-phone opened on the watch](evidence/simulator-launch/pair-remote-phone-to-watch.png) | ![VIEW probe://target/from-watch opened on the phone](evidence/simulator-launch/pair-remote-watch-to-phone.png) |

| Phone probe | Watch probe |
| --- | --- |
| ![phone probe results](evidence/simulator-launch/pair-probe-phone.png) | ![watch probe results](evidence/simulator-launch/pair-probe-watch.png) |

The probes found five simulator bugs, all fixed:

- **`ResultReceiver` through a `Parcel`.** Robolectric's shadow makes `send` call the receiver's
  own `onReceiveResult`. `RemoteActivityHelper` parcels its receiver before handing it over, and
  the copy is a plain `ResultReceiver` that forwards over its binder, so the result was dropped
  and the launch's future never completed. `SimulatorShadowResultReceiver` runs the framework's
  `send`.
- **A shadow parameter typed `Object[]`.** Robolectric matches a `@ClassName` parameter only when
  it is declared `Object`, so `ShadowGoogleApiAvailability` never applied.
- **`com.google.wear.Sdk`.** With `android.hardware.type.watch` reported, androidx.wear reads the
  Wear OS system library on API 34+, which a device has and Robolectric does not.
- **`unbindService` calling `onServiceDisconnected`.** Robolectric's default; `BridgingManager`
  unbinds in `onServiceDisconnected`, so the two called each other until the log filled the disk.
  The simulator now behaves as a device.
- **A bind reported as a failed start.** Robolectric logs a bind as a start too; the binds to the
  simulator's own Wear OS app services are now recognized as binds.

## Evidence: MeshCore

`meshcore-debug.apk` with `meshcore-wear-debug.apk`, `--sdk 36`. Three causes, in order, kept the
watch from ever reaching its phone:

1. **The watch skips the Data Layer under Robolectric.** `WearAppGraph` checks `Build.FINGERPRINT
   == "robolectric"` (its preview renderer's test) and then uses `PreviewWearClient`, canned
   data, instead of Horologist's `WearDataLayerRegistry`. That is what the earlier "Radio not
   connected" was. `launch --fingerprint <device fingerprint>` makes it take the real client.
2. **No `horologist_phone` capability (a MeshCore bug).** The watch's gRPC channel targets
   `TargetNodeId.PairedPhone`, which Horologist resolves as
   `getCapability("horologist_phone", FILTER_ALL).nodes.singleOrNull()`. The phone APK has no
   `android_wear_capabilities` resource at all, so on a device too the watch finds no phone:
   "Phone not reachable". MeshCore uses neither `PhoneDataLayerAppHelper` nor
   `WearDataLayerAppHelper`; only the registry and `datalayer-grpc`. The fix belongs in the
   phone app's `res/values/wear.xml`. In the simulator `{"cmd":"dataLayer","addCapability":
   "horologist_phone"}` on the phone stands in for it.
3. **A request to a `MESSAGE_RECEIVED` service (a simulator gap, fixed).** The phone's
   `MeshcoreWearDataService` (`BaseGrpcDataService`) declares `MESSAGE_RECEIVED` for
   `/WearGrpcService/…`, as Horologist's samples do, and Play services delivers `sendRequest`s
   there too; the simulator only looked for `REQUEST_RECEIVED`.

With all three addressed, the watch's `GetConnectionStatus` crosses as a `sendRequest`, the
phone's service answers (6 bytes), and the watch shows the phone's real state: its radio is not
connected, since the simulator has no BLE device in range.

| No `horologist_phone` | Answered by the phone |
| --- | --- |
| ![MeshCore watch: phone not reachable](evidence/simulator-launch/pair-meshcore-watch-unreachable.png) | ![MeshCore watch: the phone's radio state](evidence/simulator-launch/pair-meshcore-watch-via-phone.png) |

## What is missing

- **Fidelity gaps, all in `DataLayer`.** `getCapability` leaves out the local node (Play
  services includes it when the local app has the capability); filters on `getCapability` /
  `getAllCapabilities` (`FILTER_REACHABLE` vs `FILTER_ALL`) are ignored, since every peer is
  reachable; `onPeerConnected` / `onConnectedNodes` are not delivered; a data item's
  `TYPE_CHANGED` is delivered even when its bytes did not change; `getNodeId` answers null.
- **`ChannelClient`** (streams between nodes) is not simulated; `getChannelClient` still returns
  Play services' real client, which fails with `API_UNAVAILABLE` (17).
- **Wear SDK services.** `RemoteActivityHelper.availabilityStatus` reports `STATUS_UNKNOWN`:
  the Wear SDK's `RemoteInteractionsManager` (Wear OS 6) is not simulated.
- **Things only the system does.** No browser or Play Store opens a remote `https:` / `market:`
  intent (reported, answered OK); no companion app exists for Horologist's `startCompanion`
  (`APP_HELPER_RESULT_NO_COMPANION_FOUND`).
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
- **Driving it from the control channel** is `--data-layer agent` ([`../SIMULATOR.md`](../SIMULATOR.md)):
  one simulator, with the agent playing its peers through the same frames the hub relays
  (`DataLayerAgent`). `ChannelClient` streams are not among them.
- **MeshCore's own handshake** (above) needs `horologist_phone`, which its phone does not
  declare; the simulator surfaces that rather than papering over it.

## Estimate

The prototype covers what both apps use. To make it the default for `launch --pair`:

| Work | Estimate |
| --- | --- |
| Fidelity gaps above, with unit tests over a fake link | 1–2 days |
| `ChannelClient` (pipes over the hub) | 2 days |
| Minified APKs (mapping-file lookup of `Wearable` and the clients) | 2–3 days |
| MCP server / `docs/SIMULATOR.md` integration of the paired session | 1 day |
