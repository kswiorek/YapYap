## Governance Tests

Executable tests that gate the build beyond unit behavior. Two governance standards live elsewhere with their full
detail: layer dependency and data-ownership enforcement by ArchitectureTest (see architecture/dependencies.md), and the
GUI import allow-list contract (see frontend/gui-boundary.md). This file covers the remaining project-specific testing
rules, deriving from docs/guide.md §9, docs/e2ee.md (Test coverage), docs/room events.md and docs/transport.md.

### New wire types need codec round-trip tests

A new envelope or payload type gets a codec with round-trip tests, plus an observability profile if it adds routing
metadata. This is step 5 of the writing-new-code checklist and step 1 of the envelope-redesign implementation order. In
practice: 22 commonTest files assert encode/decode round-trips plus reject-bad-input cases (e.g.
`MessageEnvelopeCodecTest`: `messagePayload_text_encodeDecode_roundTrip`, `decode_rejectsTruncatedBuffer`).

Sources: docs (guide.md §9; envelope-redesign.md §8), code · confidence 90.
Evidence:

- guide.md §9 step 5: "Wire types go in `protocol`. A new envelope or payload gets a codec with round-trip tests and an
  observability profile if it adds routing metadata."
- envelope-redesign.md §8 (Implementation order, step 1): "protocol: `BinaryEnvelope` v2 … per-family routing-header
  codecs; content schemes; `contentId`; … observability profiles; codec round-trip tests."
- code: every codec gets encode/decode round-trip plus reject-bad-input tests (MessageEnvelopeCodecTest:
  `messagePayload_text_encodeDecode_roundTrip`, `decode_rejectsTruncatedBuffer`).

### Negative-test discipline for verification and visibility

Verdict expectations follow the authenticity-only discipline: ignored events are stored VERIFIED (authentic, no effect);
only proven forgeries are REJECTED. Each serving/rendering path gets its own negative test (done-criteria d3):
out-of-closure content is never returned by any render path — page, preview, live window insert, notification event —
each path its own test; never-members are never rendered as normal; bounded serve never leaks past the removal closure;
non-admin ops are published-and-ignored (stored VERIFIED, no shadow effect).

Sources: docs (global events.md §9; room events.md §3, §8) · confidence 88.
Evidence:

- global events.md §9 (Negative tests, done-criteria d3): "Verdict expectations follow the authenticity-only discipline:
  ignored events are stored VERIFIED (authentic, no effect); only proven forgeries are REJECTED."
- room events.md §3: "out-of-closure content is never returned by any render path — page, preview, live window insert,
  notification event — each path its own test (the CTE text is duplicated across the named queries; SQLDelight has no
  fragments)."
- room events.md §8 (step 5): "negative tests for the hide-policy (done-criteria d3)."

### E2EE required-test matrix (tamper, reorder, race, pinning)

The E2EE layer's documented test coverage is normative: X3DH 3-DH/4-DH agreement and SPK/OPK mismatch rejection; ratchet
round-trip, out-of-order, snapshot restore, header/body tamper rejection; session-manager simultaneous init, handshake
re-attach, epoch-2 upgrade/binding/re-offer, confirmation supersede, housekeeping; OPK lifecycle and expired-offer
prune; AEAD tamper rejection. Specific named tests are cited in the doc for each hardening behavior (e.g.
`decrypt_rejectsTampered*`, `epoch2_bootstrap_rejectsMismatchedSignedPreKeyId`).

Sources: docs (e2ee.md — Test coverage) · confidence 87.
Evidence:

- e2ee.md (Test coverage): "`DoubleRatchetSessionTest` | Round-trip, bidirectional, out-of-order, snapshot restore, skip
  gap limit, header/body tamper rejection" plus the integration-test backlog table where P0/P1 rows are marked done with
  named tests.
- e2ee.md (Ratchet header AAD): "Tests: `DoubleRatchetSessionTest` — `decrypt_rejectsTampered*` and
  `decrypt_outOfOrder_rejectsTamperedHeaderOnSkippedMessage`."
- e2ee.md (P0.3): "Tests: `epoch1_bootstrap_rejectsMismatchedWireSessionEpoch`,
  `epoch2_bootstrap_rejectsMismatchedSignedPreKeyId`."

### Paired fold fuzzers re-run on any seal fix; extractions proven unchanged

The two seal-algebra implementations (global and room folds) are kept honest by paired dynamics fuzzers: a seal fix
lands in both folds and both fuzzers re-run. The room fuzzer needs no crypto stub (the fold reads the verdict column —
synthetic rows suffice). Any extraction of shared fold machinery is its own mechanical commit proven by the global
dynamics fuzzer (20k seeds) plus projector tests run unchanged.

Sources: docs (room events.md §4, §8) · confidence 87.
Evidence:

- room events.md §4: "The two implementations are kept honest by the paired dynamics fuzzers, not by shared code: **a
  seal fix lands in both folds and both fuzzers re-run.** The room fuzzer needs no crypto stub (the fold reads the
  verdict column — synthetic rows suffice)."
- room events.md §8: "extract it at that moment, as its own mechanical commit, proven by the global dynamics fuzzer (20k
  seeds) + projector tests run **unchanged**."

### Live integration tests are opt-in; WebRTC natives can be flaky

Integration tests (live loopback Tor, local two-peer WebRTC, live Tor + WebRTC, real OS keyring, live router/file flows)
are excluded from the default jvmTest run and execute only with `-PintegrationTests=true`. Tests of this kind must be
named `*IntegrationTest` and added to the default-off exclusion list in core/build.gradle.kts so they never run in
normal builds or CI. Some live/native WebRTC tests can be flaky with thread-attach warnings from webrtc-java — treat
flakes accordingly. Existing router tests (`DefaultRouterContractTest`, `DefaultRouterOutboxTest`,
`DefaultRouterE2eeIntegrationTest`) remain the safety net for router refactors; add focused unit tests for extracted
classes where practical (recording transports, a fake dispatcher).

Sources: docs (transport.md; router.md), config, CI config · confidence 84.
Evidence:

- core/build.gradle.kts:
  `val integrationTestsEnabled = (findProperty("integrationTests") as? String)?.equals("true", ignoreCase = true) == true`;
  `jvmTest` excludes 8 patterns (*TorRealBackendTransportIntegrationTest, *KmpTorBackendLifecycleIntegrationTest, *
  WebRtcInMemorySignalingIntegrationTest, *DefaultRouterLiveIntegrationTest, *FileSmallPushLiveIntegrationTest, *
  DefaultKeyStoreJavaKeyringIntegrationTest, *JvmEncryptedDriverFactoryIntegrationTest, *OrchestratorLoopbackBootTest)
  unless the property is set.
- .run/YapYap integration.run.xml: Gradle run config running allTests with `-PintegrationTests=true`.
- transport.md (Testing): "Some live/native WebRTC tests can be flaky with thread-attach warnings from webrtc-java."
- router.md (Testing strategy): "Existing tests remain the safety net … Focused unit tests for extracted classes can be
  added where practical."

```shell
./gradlew :core:jvmTest -PintegrationTests=true --rerun-tasks
# New live-backend test: name it FooIntegrationTest and add
# excludeTestsMatching("*FooIntegrationTest") to the default-off list
```
