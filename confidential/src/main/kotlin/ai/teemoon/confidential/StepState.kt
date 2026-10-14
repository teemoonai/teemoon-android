// ports: teemoon/Confidential/AttestationSummary.swift @ v1.0.1 (StepState; the rest of the file is AttestationSummary.kt)
package ai.teemoon.confidential

/** Resolution state of a single verification step. */
enum class StepState { DONE, LIVE, PENDING, STUCK }