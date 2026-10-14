// The junit4 compose rules are deprecated in favour of the `v2` package, whose idling semantics differ.
// The journeys' waits were tuned against THIS rule on two devices (docs/JOURNEYS.md), so the migration is a
// device-verified change, not a compile fix. The deprecation lives here once instead of in every suite.
package ai.teemoon.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

@Suppress("DEPRECATION")
fun emptyComposeRule(): ComposeTestRule = createEmptyComposeRule()

@Suppress("DEPRECATION")
inline fun <reified A : ComponentActivity> androidComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<A>, A> = createAndroidComposeRule<A>()
