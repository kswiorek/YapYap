# Development Roadmap

## Current State

- **Version**: 0.0.6 (pre-release); 299 commits since 2026-04-21, last commit 2026-11-25
- **Key Features** (working, per `docs/guide.md` §8 and codebase analysis):
    - Tor transport (embedded kmp-tor) and WebRTC data channels with signaling over Tor
    - Router: outbox with retries, store-and-forward relays, ACK/NACK, dedup, pings, peer availability/uptime scoring
    - 1:1 and group E2EE (X3DH + Double Ratchet, per-device fan-out)
    - DAG-based room history, global + room folds, sponsor-QR onboarding/recovery, pull-based sync
    - SQLCipher-encrypted persistence with keyring-held master key; typed key hierarchy
    - Compose Multiplatform desktop GUI (chat, setup, settings, room creation, quick switcher)
    - Architecture enforcement via governance tests (`ArchitectureTest`, `PublicApiSurfaceTest`, `GuiApiBoundaryTest`)
- **Recent Updates** (Nov 2026): envelope redesign — single signed `BinaryEnvelope` for all 5 packet families
  (`docs/envelope-redesign.md`); file-transfer redesign implemented; routing side reviewed and cleaned up
  (`docs/file/cleanup.md`), orchestrator side
  pending

## Planned Enhancements (Next 3-6 Months)

### High Priority (owner-selected)

- [ ] **Review & finalize the file branch** — routing side done (`docs/file/cleanup.md`, Nov 2026); remaining: review
  `orchestrator/file/*` (the coordinator's long functions and the umbrella renames deferred in cleanup.md §6); clean
  `[File cleanup]` TODOs (send-state timing dependency in
  `DefaultMessagingService`, async-loop questions in `FileSendService`, TTL-cleanup removal pending in `FileRetention`/
  `FilePins`); add the missing soak test matrix (`docs/file/design.md` was rewritten to the seals-and-hops model,
  Nov 2026)
- [ ] **Wire the GUI file-transfer surface** — `FileTransferService` exposes progress/accept/decline but no screen calls
  it yet (`docs/guide.md` §8)

### Medium Priority (from project analysis; not yet owner-prioritized)

- [ ] **Mobile behaviors** — push notifications, background behavior, network monitoring on Android/iOS (guide §8 "not
  done"); re-enable ProGuard/R8 (currently disabled, documented reflection issues)
- [ ] **E2EE backlog** (`docs/e2ee.md`) — signed-prekey rotation, epoch lifecycle work
- [ ] **Docs drift fixes** — `docs/e2ee.md` still references old `org.yapyap.backend.*` package paths; README transport
  section is a TODO; README (20–40) vs guide (~10–20) user-count reconciliation (project docs here use 20–40 per owner
  decision)

### Technical Debt

- [ ] **Unreviewed orchestrator file code** — treat `orchestrator/file/*` as provisional; do not mine for conventions or
  quality examples (`routing/file/*` was reviewed, `docs/file/cleanup.md`)
- [ ] **ArchitectureTest `knownDebt` list** — only-shrinks by design; keep shrinking it
- [ ] **Dangling items** — unused `spaces` table; real WebRTC health (`TODO [Finishing touches]` in `DefaultRouter`)

## Future Considerations

- **AV/media**: WebRTC session contracts exist; media tracks do not (Sprint 6/7 territory in the original plan)
- **Remaining hardening** (early-plan Sprint 7 flavor): watchdog restart (still a TODO in `VaultGraph`), clearnet
  fallback
- **Scalability**: the design assumes a small network (20–40 peers); larger networks are explicitly out of scope

---
**Roadmap provenance**: `docs/sprints.mmd` (Sprints 0–7) is the early-planning roadmap — historical, and it diverges
from what was actually built (group E2EE is per-device ratchet fan-out, not Megolm-style sender keys; routing is
Tor-default, not WebRTC-first). Treat it as history; the current state of play comes from `docs/guide.md` §8 "Feature
status" plus git history.

*Assessment based on project analysis performed 2026-11-25*
