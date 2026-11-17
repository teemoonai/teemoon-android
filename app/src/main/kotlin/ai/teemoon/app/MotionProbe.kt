// What the motion check reads beside a node's bounds (card motion-pass, the maintainer 2026-11-13: "automate validation that the
// animations in general are good"). Opacity is drawn, not laid out, so a node whose opacity animates says it here; the
// check (app/src/test/.../motion) reads it frame by frame. Semantics only: nothing is drawn or announced from it.
package ai.teemoon.app

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/** A node's current opacity, 0..1, for the motion check. */
val MotionAlpha = SemanticsPropertyKey<Float>("MotionAlpha")
var SemanticsPropertyReceiver.motionAlpha by MotionAlpha
