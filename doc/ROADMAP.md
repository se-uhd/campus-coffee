# Roadmap — planned and candidate additions

This file is the running list of features we intend to add but have not built yet. It is deliberately
lighter than the dated design notes in this directory: an entry here is a **candidate**, not a committed
design. It exists so future additions have a single place to live before they are picked up.

## How an entry graduates

When we start building an item, it moves out of this list and becomes:

1. a dated design note, `doc/YYYY-MM-DD_<topic>.md` (the full design, in the style of
   `2026-07-01_revert-last-recorded-change.md`);
2. a `## [Unreleased]` entry in `CHANGELOG.md`, promoted to a `## [x.y.z]` header with the matching
   `gradle.properties` version bump when the release is cut (the two are coupled by the version-sync CI
   check).

Remove the item from this file in the same change that lands it, so the roadmap only ever lists what is
still ahead.

## Candidate additions

### Browsable version history with revert to any version

Today's revert (`POST /api/{res}/{id}/revert?version={v}`) undoes only an entity's **last** recorded
change. The next step is to expose an entity's **full** history and let a curator jump back to any point
in it.

- **Browse:** `GET /api/{res}/{id}/history` returns the ordered list of an entity's recorded changes as
  `HistoryEntryDto(seq, changeType, timestamp[, snapshot])`, so a client can see every version, not just
  the current one.
- **Revert to any version:** `POST /api/{res}/{id}/revert?to={seq}&version={v}` targets a past version by
  its stable event `seq` (a log address), while `version` stays the optimistic guard ("unchanged since I
  looked," else `409`).

This is designed to be a **rework-free add**: the last-change revert was built with the seam already in
place (see `doc/2026-07-01_revert-last-recorded-change.md`, "History-ready seam").

- `EventRepository.findByEntityTypeAndEntityIdOrderBySeqAsc` already returns the **full** ordered history;
  the current endpoint reads only its tail.
- `EventSourcedReverter` already implements and unit-tests the DELETE-then-INSERT branch (undo a deletion
  by re-appending the prior snapshot). The last-change endpoint never reaches it, but reverting to an
  older version does.
- The compensating-event mechanism, the read-model projection, and the optimistic guard all carry over
  unchanged; only the target selection (the tail vs. a chosen `seq`) is new.

Open questions to settle in the design note:

- **Payload:** does browsing need the full snapshot per entry, or just the metadata (`seq`, `changeType`,
  `timestamp`)? Metadata-only is cheaper and keeps snapshot bodies off the wire.
- **Approval-driven Review changes:** the last-change revert already refuses to undo an approval-count
  UPDATE (the approval workflow owns that state); the history view has to decide whether such entries are
  shown as non-revertible or filtered out.
- **Role gating:** reuse the per-resource matrix (`MODERATOR` for POS and reviews, `ADMIN` for users) for
  the revert; decide the gate for the read (`GET .../history`).
- **Pagination:** a long history may need paging on `GET .../history`.
