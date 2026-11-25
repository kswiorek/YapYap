## Documentation

How the project's own docs are maintained and trusted. This file derives from docs/guide.md (intro, §8, §9) and
CLAUDE.md — the conventions that keep the docs the source of truth for the code.

### guide.md is the rules-of-the-code; code/doc disagreement is a bug

docs/guide.md describes the design the code must follow, not the current state of any one class. When the code and the
guide disagree, treat that as a bug in one of them and raise it — do not silently follow the code.

Sources: docs (guide.md intro) · confidence 92.
Evidence:

- guide.md (intro): "It describes the design, not the current state of any one class: when the code and this guide
  disagree, treat that as a bug in one of them and raise it, don't silently follow the code."

### Keep the docs honest (same-change rule)

A change that moves a rule in guide.md updates the guide in the same change; mechanism details go in the layer's own doc
(transport.md, router.md, e2ee.md, envelope-redesign.md, global events.md, room events.md, gui-api.md).

Sources: docs (guide.md §9) · confidence 92.
Evidence:

- guide.md §9 step 7: "**Keep the docs honest.** A change that moves a rule in this guide updates the guide in the same
  change; mechanism details go in the layer's own doc."

### envelope-redesign.md is the design of record for envelope + file topics

envelope-redesign.md (agreed 24 November 2026, implemented on the files branch) is the design of record for the envelope
layering and the small-file path until file/design.md is rewritten on it; where the two disagree, envelope-redesign.md
is the newer decision. guide.md §8 likewise flags file/design.md as pending rewrite. Treat docs/file/ content as
provisional — do not extract standards from it.

Sources: docs (envelope-redesign.md intro; guide.md §8) · confidence 90.
Evidence:

- envelope-redesign.md (intro): "This document is the design of record for the envelope layering and the small-file path
  until `file/design.md` is rewritten on it (§10); where the two disagree, this one is the newer decision."
- guide.md §8 (Not done yet): "`file/design.md` rewritten on seals and hops (`envelope-redesign.md` is the newer
  decision where they differ)."

### Check feature status before assuming a surface exists

guide.md §8 carries an explicit working/not-done list; consult it before building on a documented surface. Not done yet
includes: the GUI file surface (no screen calls FileTransferService's progress/accept-decline/open yet), file-transfer
soak and test matrix, audio/video media tracks, mobile push/background behavior, signed-prekey rotation and remaining
epoch lifecycle work, metadata minimization beyond current observability profiles (desirable, not a v1 blocker).

Sources: docs (guide.md §8) · confidence 88.
Evidence:

- guide.md §8: "Not done yet: - GUI file surface (progress, accept/decline, open: the core's `FileTransferService` has
  them, no screen calls them yet) … - Audio/video media (session contracts exist; media tracks do not). - Signed-prekey
  rotation and the remaining epoch lifecycle work … - Metadata minimization beyond the current observability profiles
  (desirable, not a v1 blocker)."

### AI assistants ground in .maister docs and run maister workflows via the skill tool

Before writing or changing any code — even quick requests — read .maister/docs/INDEX.md, then the specific files it
points to that are relevant to the task (standards AND project/domain docs; the index alone is not enough). Follow the
standards; if one conflicts with the task, ask the user. Suggest adding a standard when a recurring
pattern/fix/convention isn't yet captured (then `/maister/standards-update` on approval). When any `/maister/*` command
is invoked, execute it via the skill tool immediately — never skip workflows for "straightforward" tasks.

Sources: docs (CLAUDE.md) · confidence 89.
Evidence:

- CLAUDE.md: "Before writing or changing any code … ground yourself in the project's documentation: 1. Read
  @.maister/docs/INDEX.md … 2. Then open and read the specific files it points to … The index alone is not enough."
- CLAUDE.md (Standards Evolution): "When you notice recurring patterns, fixes, or conventions during implementation that
  aren't yet captured in standards — suggest adding them … If approved, invoke `/maister/standards-update` with the
  identified pattern."
- CLAUDE.md (Maister Workflows): "When any `/maister/*` command is invoked, execute it via the skill tool immediately —
  do not skip workflows for 'straightforward' tasks."
