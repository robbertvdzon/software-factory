# Roadmap

## Purpose

Give the customer direct, visible influence on the order of product epics without hiding the
roadmap process' recommendation or technical dependencies.

## Layout

- Page header `Roadmap` with a `+` action for a new epic.
- A legend states the 75% customer / 25% process weighting and explains dependency arrows.
- Epics are cards in a pannable and zoomable dependency graph. A predecessor is always positioned
  to the left of the epic that depends on it.
- Each card shows the definitive roadmap rank, short title, customer rank, process rank and a
  blocked marker when an unfinished dependency exists.
- Arrow buttons on the card move the customer rank one position; intermediate epics are
  automatically renumbered.

## Epic detail

Clicking a card opens a dialog with the short title, extended description, editable customer rank,
read-only process rank, status and dependency checkboxes. Saving is atomic. A rejected dependency
cycle stays visible as an error in the dialog and does not partially update the epic.

When the definitive rank differs, the dialog shows the backend explanation. Dependencies are
hard constraints; the customer preference always remains visible even when it cannot be the
execution order.
