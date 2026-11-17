// ports: teemoon/Confidential/AttestationSummary.swift @ v1.0.1 (StepState; the rest of the file is AttestationSummary.kt)
package ai.teemoon.confidential

/** Resolution state of a single verification step. */
/** UNCHECKED: a check that couldn't run (an Intel outage) — the orange "couldn't check" mark, never a pass (trust-unchecked-glyph). */
enum class StepState { DONE, LIVE, PENDING, STUCK, UNCHECKED }