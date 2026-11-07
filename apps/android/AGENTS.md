# apps/android — the client

Kotlin + Compose, `minSdk 24`, drives the xray core through `libv2ray.aar` and owns
`ShieldVpnService` (the tunnel) plus `VpnViewModel` (what the user sees).

## Missing from this checkout

`app/libs/*.aar` (~57 MB) is not in git, so **the module cannot compile from the repo alone**. It
lives in the operator's build directory until it is either tracked with Git LFS, published as a
release asset, or rebuilt from upstream — `docs/adr/0001` records the choice. CI's android job
skips itself with a warning rather than pretending to pass.

## Building and testing

The project is compiled from a copy, so sources are synced first:

```
bash tools/android-tests.sh      # syncs here -> $BUILD, runs gradle testDebugUnitTest, counts the XML
bash tools/ptunnel test-android
```

`BUILD`, `GRADLE`, `JAVA_HOME`, `ANDROID_HOME` come from `tools/env.local.sh` (gitignored; copy
`tools/env.example.sh`). Never build the APK on a VPS — the boxes are 1–2 GB and it is not their job.

## Where the difficult behaviour lives

- `vpn/ShieldVpnService.kt` — the tunnel's life: health watchdog, recovery ladder, and the rule that
  **the session is never ended by the app itself**. Every `stopVpn()` added here needs a reason the
  user could act on; a withdrawn VPN permission is one, a dead server is not.
- `vpn/TunnelRecovery.kt` — the rungs (`SAME_CONFIG`, `OTHER_BOX`, `FRESH_MULTI`, `KCP_ONLY`,
  `CACHED_CDN`, `FRESH_CDN`) and the two country searches: `otherBox()` escapes a box that cannot
  carry a tunnel, `fasterPath()` optimises a live one and must respect a manual choice.
- `network/PathLadder.kt` — the *pure* ordering and threshold rules. Put new decisions here, not
  inline, so they can be tested: `fasterPathWins`, `isDegraded`, `plan`, `orderedEndpoints`.
- `network/VpnApiService.kt` — endpoint failover, pinned DNS, and the signed update metadata
  (unsigned metadata is refused, full stop).
- `vpn/TunnelMtu.kt` — why the TUN MTU is derived from the link and never a constant.

## Rules specific to this app

- A timeout wrapped around a blocking call does not cancel it. Use the worker-thread pattern in
  `probeLatencyBounded` or bound it in the client (`connectTimeout`), not in coroutines.
- CONNECTED means one thing only: a real `generate_204` through the tunnel answered.
- The country label must follow the traffic, never the wish — see `syncLabelToServedEndpoint`.
- Version bump = `versionCode` + 1, `versionName = "0.NN"`, in the same commit as the release.
