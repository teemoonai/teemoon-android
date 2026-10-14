// ports: teemoon/Views/Chat/ScrollTrace.swift @ v1.0.1 — TranscriptTrace.geometry and ScrollTraceFile (the [scrolltrace]
//        line, same columns in the same order); StreamTrace / WireTrace (the pacer and wire curves) are not ported yet
package ai.teemoon.app

import android.content.Context
import java.io.File

/**
 * Ground truth for "how does the transcript actually move while a reply streams" — the question behind every
 * scroll-smoothness change. It exists because the obvious ways to answer it are wrong: a test that polls the tree
 * waits for idle on every query and perturbs the thing it measures until only the perturbation is left (iOS measured
 * 761pt of real drift read back as 178pt); a screen recording only recovers motion it can search for, and a jump larger
 * than the search window comes back as a small wrong number — exactly backwards, since jumps are the defect.
 *
 * The list knows its own offset. Asking it costs one line per geometry change, and the answer is read out of band —
 * `tools/check_scroll_trace.py`, with the app left completely alone.
 *
 * Debuggable builds only, and off unless the launch intent carries `scroll_trace`, so a shipping build cannot reach it
 * and an ordinary debug run does not pay for it. Each line is
 *
 *     [scrolltrace] t=<s> offset=<dp> contentH=<dp> visibleH=<dp> rows=<n> gen=<0|1> intr=<0|1> drag=<0|1> follow=<0|1>
 *
 * — the SAME line as iOS, so the overscroll metric (samples where offset + visible > contentH + 250) and the walk-away
 * hand-off verdict are comparable across the two transcripts. That comparability is the whole value: the SwiftUI
 * transcript traced ~600 overscrolled samples per generation (the blank screen) and the collection view's target is 0.
 *
 * `contentH` is what a LazyColumn can know: the offset plus the last row's bottom while that row is laid out, and a
 * lower bound (the viewport's bottom edge, `past = 0`) while it is not — a lazy list has no content size to read.
 */
object ScrollTraceEnabled { @Volatile var value = false }

object TranscriptTrace {
    fun geometry(offset: Double, contentHeight: Double, visibleHeight: Double, rows: Int, generating: Boolean, interrupted: Boolean, dragged: Boolean, following: Boolean) {
        if (!ScrollTraceEnabled.value) return
        val t = System.nanoTime() / 1e9
        ScrollTraceFile.append("[scrolltrace] t=$t offset=$offset contentH=$contentHeight visibleH=$visibleHeight rows=$rows gen=${if (generating) 1 else 0} intr=${if (interrupted) 1 else 0} drag=${if (dragged) 1 else 0} follow=${if (following) 1 else 0}")
    }
    /** A discrete event on the same clock — a turn's start and end — so the analysis can find the hand-off. */
    fun event(name: String) {
        if (!ScrollTraceEnabled.value) return
        ScrollTraceFile.append("[streamevent] t=${System.nanoTime() / 1e9} $name")
    }
}

/** The trace in the app's private files (`scrolltrace.log`), truncated when tracing is enabled; pulled with `run-as`. */
object ScrollTraceFile {
    private var file: File? = null
    private val lock = Any()
    fun enable(context: Context) {
        synchronized(lock) { file = File(context.filesDir, "scrolltrace.log").also { it.writeText("") } }
        ScrollTraceEnabled.value = true
    }
    fun append(line: String) { synchronized(lock) { file?.appendText(line + "\n") } }
}
