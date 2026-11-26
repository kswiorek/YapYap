# Phase 2 Scope Clarifications

## TL;DR
Seven approach decisions resolved: the seals TODO is a naming-only fix (no ownership seam), TransferEngine gets the full per-transfer state-object redesign with iterative sketching rounds, a dedicated FileService test harness is added, guarded() stays private-per-file, the reported-dedup race is folded into the fan-out change, FileFetchService lands as ONE wave, and DeviceDirectory.isKnownNonBanned is pulled into scope alongside fileControl.

## Key Decisions
- `FileService.kt:83` "seals" TODO → rename only — the complaint is the naming collision (`seals` = SealRegistry in FileService vs `seals` = FileSealStore in SealRegistry/TransferEngine); fields renamed to disambiguate (registry by role, stores by *Store), TODO deleted. No ownership seam.
- TransferEngine → per-transfer state-object redesign — the user explicitly wants a few rounds of sketching/designing until agreement before implementation; the workflow must present the engine design iteratively.
- Dedicated FileService unit-test harness → added before/with the wiring changes.
- guarded() → private-per-file; only the two inline copies fold into their file's existing helper.
- `reported` dedup map → folded into the SealRegistry fan-out change as an owned type with its own lock.
- FileFetchService split → ONE wave — user's rationale: splitting in half with each half having to pass tests/compile pressures minimal modification and leaves more mess than we started with; do the vocabulary/scope-var/split in one comprehensive change.
- `DeviceDirectory.isKnownNonBanned` → IN SCOPE — retired (becomes a member) alongside the fileControl retirement, despite living in routing/router with a message-path consumer.

## Open Questions / Risks
- The per-transfer state-object redesign is the riskiest item; its design must survive several sketch rounds before any code lands (user's explicit requirement).
- Pulling isKnownNonBanned into scope slightly widens the diff into routing/router and the message path (MessageInboundHandler.kt:79 consumer) — accepted by the user.

## Q&A Detail

Q1 (critical, engine-ownership): user's own words — "What I meant is that there is the seals object of the SealRegistry type in FileService and seals: FileSealStore inside the SealRegistry, so two seal objects which are completely different, but named the same. I think a rename here is fine."
Q2 (critical, engine depth): "I would like the per-transfer state-object redesign, that is why my initial request to do a few rounds of sketching and designing until we agree, because it is not that simple."
Q3 (critical, FileService harness): Add dedicated harness (recommended option accepted).
Q4 (guarded placement): Private-per-file (recommended accepted).
Q5 (reported map): Fold in (recommended accepted).
Q6 (fetch split): One wave — "from my experience splitting those in half with each half having to pass tests and compile means that we try to modify as little as possible leaving more mess than we started with."
Q7 (isKnownNonBanned): In scope.
