# Project Vision

## Overview

YapYap is a decentralized, serverless messaging app for small trusted networks of 20–40 people: every device runs the
same Kotlin core, text and small files travel over Tor with store-and-forward relays, WebRTC adds low-latency chat and
large file transfers, and all communication — including group chats — is end-to-end encrypted.

## Current State

- **Age**: ~7 months (first commit 2026-04-21)
- **Status**: Active development, pre-release **v0.0.6** (299 commits, last commit 2026-11-25)
- **Users**: none yet — pre-release; designed for a personal/small trusted network, with optional headless relay nodes
  (e.g. Raspberry Pis) anchoring availability
- **Tech Stack**: Kotlin 2.3.20 (KMP) core + Compose Multiplatform 1.10.3 GUI; SQLDelight/SQLCipher persistence;
  embedded Tor + WebRTC transports; X3DH + Double Ratchet E2EE
- **Platform reality**: desktop JVM works end-to-end; Android/iOS compile but mobile behaviors (push, background,
  network monitoring) are explicitly "not done" (`docs/guide.md` §8)

## Purpose

Serverless, metadata-conscious communication for a small trusted network. The design premise (see `README.md`):

- No servers: every peer runs the same core; a few headless relay peers (chosen by uptime score) store-and-forward
  messages so offline peers still receive them; relayed messages prune after ~2 days or a user-set size limit.
- Two-tier identity: accounts own devices, all identities are known to every peer via a global event room; sponsors
  onboard newcomers by scanning a QR code — no server accounts, no phone numbers.
- All traffic is end-to-end encrypted (X3DH + Double Ratchet, per-device fan-out for groups), hop-signed, and travels
  over Tor by default; WebRTC (signaled over Tor) is the low-latency / large-file path.
- Message history is a per-room DAG; delivery is effectively-once via a pull path (frontier-advertising pings + sync
  requests) with the push outbox as a latency optimization.

The rules of the code — layering, data ownership, delivery contract, writing-new-code checklist — live in
`docs/guide.md`, and are enforced by governance tests, not just prose.

## Goals (Next 6-12 Months)

Owner-selected primary goals:

1. **Review & finalize the file branch** — the file-transfer implementation (`orchestrator/file/*`, `routing/file/*`)
   was freshly redesigned (Nov 2026); the routing side has been reviewed and cleaned up (`docs/file/cleanup.md`), the
   orchestrator side is still **unreviewed**; remaining work items: review against
   `docs/envelope-redesign.md` (design of record, 2026-11-24), clean the `[File cleanup]` TODOs (send-state timing
   dependency, async-loop questions, retention/pins TTL cleanup), add the missing soak test matrix
   (`docs/file/design.md` now describes the seals-and-hops model as built).
2. **Wire the GUI file-transfer surface** — `FileTransferService` already exposes progress/accept/decline, but no screen
   calls it yet (`docs/guide.md` §8).

## Evolution

- `docs/sprints.mmd` is the **early-planning roadmap** (kanban, Sprints 0–7: storage/crypto primitives → router MVP + 1:
  1 E2EE → sync + DAG → boot recovery → RBAC/ban → large files + group keys → WebRTC resilience/AV → hardening/release).
  It reflects intent at planning time, not current state: Megolm-style group sender keys were never built (a per-device
  double-ratchet fan-out was), and routing became Tor-default rather than WebRTC-first.
- The wire protocol was consolidated in Nov 2026 into a single signed `BinaryEnvelope` covering all 5 packet families
  (MESSAGE/SIGNAL/FILE/SYSTEM/BOOTSTRAP) — see `docs/envelope-redesign.md`.
- **Code-review standing**: the strictly reviewed, exemplar part of the codebase is the core *excluding* the file
  implementation — routing (router/outbox/presence/sync/message), orchestrator (dag/fold/pipeline/boot), crypto,
  protocol, persistence, wiring. File-transfer code is provisional until reviewed and should not be mined for
  conventions.
