# Phase 1 Clarifications

## TL;DR
Four scope/approach decisions resolved: the cleanup is scoped to `routing/file` only (kernel TODOs become documented follow-ups), the `offerOutcome` TODO is resolved by documenting the mailbox idiom, `FileFetchService` gets the full split, and the workflow continues into implementation once the sketch is agreed.

## Key Decisions
- Scope = `routing/file` only — the flagged TODOs plus same-theme issues inside the file package (incl. `FileFetchService`, `RelayDoor`, `FileRequestProcessor`); DefaultRouter WebRTC/presence TODOs, BootstrapSender keying, MessagePullProcessor dock debt, RelayDepositor fold are documented as follow-ups, out of this change.
- `FileService.kt:154` "why not override?" → document the idiom, delete the TODO — matches the reviewed siblings (MessageService/BootstrapService); no interface change, no API churn.
- `FileFetchService` (1007 lines) → full split included — requester/holder/frame-routing/serve-gate pieces, fixing the Answer/FetchResult duplication, grace-window double-handling, and the mutable `scope` var.
- Workflow end point → continue into implementation — after the sketch passes review at the spec/plan gates, this run proceeds to execute it.

## Open Questions / Risks
- None pending from Phase 1; the sketch's own decision points (e.g. exact FileFetchService file boundaries) surface at the spec gate.

## Q&A Detail

Q1 — Scope boundary: what should the cleanup sketch cover?
**A: routing/file only.** Kernel/router TODOs (DefaultRouter:173/216, BootstrapSender:54, MessagePullProcessor:110, RelayDepositor:15) are noted as follow-ups but stay out of this change.

Q2 — `offerOutcome` (FileService.kt:154): delete-the-TODO vs shared interface?
**A: Document the idiom, delete the TODO.** The outcome mailbox is a deliberate router-internal kernel seam shared with MessageService and BootstrapService; a KDoc line names the pattern.

Q3 — `FileFetchService` depth: full split vs light pass?
**A: Full split included.** Sketch a split into requester/holder/frame-routing/serve-gate pieces (~4–5 files, each <300 lines).

Q4 — Stop at the agreed sketch or continue into implementation?
**A: Continue into implementation.** The sketch is reviewed at the spec/plan gates; once agreed, this run executes it.
