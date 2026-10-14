# conformance/vendor

Golden vectors generated in **teemoon-ios**, never here.

- `kitVersion` in each file is the stamp this repo pins (kit stamp, ports-map
  baseline and followed release tag fold into one ref).
- Regeneration happens in teemoon-ios via `make conformance`, which is
  simulator-bound. A vector minted from Kotlin would certify the port against
  itself; that is the one failure the kit cannot survive.

Currently vendored: **kit 0.5.0**, minted 2026-10-02 in teemoon-ios on the v1.0.4 app tree (the kit
branch `conformance/kit-on-1.0.4`; its generator, `make conformance`, is not yet in the public
teemoon-ios tree — until it is, these files are the only published form of the kit) —
`crypto.json`, `quotes.json`, `collateral.json`, `sigstore.json`,
`nras.json` and `tls.json` (the recorded NRAS round trip and TLS-attestation
exchange), and since 0.4.0 `streams.json` (the recorded near.ai streams with
what the parser recovers), `catalogs.json` (the xAI/Fireworks/LM Studio
responses with the rows the adapters build at a pinned instant) and
`brave.json` (the answer/context bodies with the visible prose, sources and
context XML).
`tools/sync_conformance.py --ios ../teemoon-ios` refreshes them and stamps
`ports.json`; `KitStampTest` fails the build if any vendored class drifts from
that stamp. Not yet byte-locked by the kit: tool-call dialects, config and compose analysis —
those Android suites are ported from literal expectations.
