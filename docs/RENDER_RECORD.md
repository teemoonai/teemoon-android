# The render record

Both apps can narrate what they actually drew, and one script diffs the two narrations. It is the only layer that can
answer "does this screen have the same type, spacing and colour as iOS": a framework default shows up as a resolved
number whether or not anyone wrote it down, which no source-reading check can see.

## Android's half

`RenderRecorder` (`app/src/main/kotlin/ai/teemoon/app/RenderRecord.kt`) records, per element, the values Compose
actually resolved — type, spacing, colour token, size. It is inert in production: nothing in the app installs a
recorder, so a call site is a null check and nothing else. `RenderRecordTest` installs one under Paparazzi and writes
each screen's record to `app/build/renderRecord/` on every `./gradlew test`.

## The join: rendered text, not a hand-assigned role

Records are joined on `(screen, kind, normalised-text)`, normalised the way `tools/copy_audit.py` normalises copy:

```
key = (screen, kind, normalised-text)
```

A hand-assigned role key (`where.get.row.caption`) would be a third vocabulary to keep in step with two codebases,
and a mis-keyed role pairs two different elements while the diff reports nothing. Text is what both sides already
contain. Rendered text alone can still pair the same words on two different elements, so two different elements
under one key stop the producer (`twoDifferentElementsUnderOneKeyStopTheProducer`) rather than being guessed apart.

## The iOS half, and the gate

There are no vendored iOS records yet. When one lands under `parity/render/ios/`, it carries the iOS sha it was
produced at, and `tools/render_diff.py --gate` fails on any difference — and on a record older than the `ports.json`
entry for its screen, because a stale record reads as agreement.

## What it still cannot catch

Stated up front so the record is not oversold.

- **Pixels.** Both sides can report `17pt semibold, label, 8pt padding` and still
  look different: SF Pro against Roboto, line-height rounding, Compose's default
  text padding, antialiasing. This compares declarations, not rendering.
- **Motion.** A duration can be compared; a curve across two platforms cannot.
- **Anything drawn outside `DesignKit`/`Theme`.** A hand-rolled `Row` inside an
  instrumented screen is invisible. That is itself a finding — CONTRIBUTING.md already
  says build on the design system — but the record cannot report what it never
  sees.
- **Elements with neither text nor a glyph**, by construction — and this is a
  bigger hole than it sounds. Container padding, spacers, dividers and stack
  spacing are structurally invisible to this record. The chip bug carried text
  so it would be caught; plenty would not. **A green record does not mean the
  screens match**, and anyone reading one should know that before they trust it.
