package app.trove.debrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.trove.debrid.ui.downloads.CountPill
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the 0.1.8–0.1.13 blank-screen loop, mirroring the
 * upstream test in androidx/androidx commit c61c795 ("Fix TabRow
 * post-measure intrinsics and Tab intrinsics", bug 576397706).
 *
 * The production tab content is reproduced exactly: a PrimaryScrollableTabRow
 * whose tabs each hold a Row of a label Text and a CountPill. In 0.1.12 the
 * main thread spun inside ScrollableTabRowImpl.measure for 13 seconds and
 * the first frame never drew; in 0.1.13 the same content settles.
 *
 * DEVICE-RUN ONLY. This test needs a device or emulator: it cannot run in
 * this VM and it does not run on CI (the CI workflow runs unit tests and
 * lint only). The CI guardrail for the same regression is
 * CountPillGuardrailTest, a JVM unit test. Run this test on a device with
 * `./gradlew connectedDebugAndroidTest` (or the equivalent run configuration)
 * after touching the tab row, the tab content, or the Material 3 version.
 */
class TabRowLayoutSettlesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tabRowWithCountPills_settlesWithinTwoPasses() {
        val measureCounts = IntArray(3)
        val pillCounts = intArrayOf(0, 1000, 10000)
        var selectedIndex by mutableStateOf(0)

        rule.setContent {
            PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
                repeat(3) { index ->
                    Tab(
                        modifier = MeasureCountModifier { measureCounts[index]++ },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Tab ${index + 1}")
                                CountPill(pillCounts[index], selected = selectedIndex == index)
                            }
                        },
                    )
                }
            }
        }

        // With the loop present this never returns: the main thread spins in
        // ScrollableTabRowImpl.measure, so waitForIdle times out and the test
        // fails here instead of hanging the suite.
        rule.waitForIdle()

        // Material 3 1.4.0 measures each tab twice per pass (measure, then a
        // post-measure maxIntrinsicWidth query that requests one remeasure);
        // the fixed Material 3 measures once. Three allows one incidental
        // extra pass. The loop measured unboundedly.
        measureCounts.forEachIndexed { index, count ->
            assertTrue("tab $index measured $count times in the first pass", count <= 3)
        }

        selectedIndex = 1
        rule.waitForIdle()
        measureCounts.forEachIndexed { index, count ->
            assertTrue(
                "tab $index measured $count times after a selection change",
                count <= 6,
            )
        }
    }

    /** Counts measure calls and otherwise measures exactly like the child. */
    private data class MeasureCountModifier(val onMeasure: () -> Unit) :
        ModifierNodeElement<MeasureCountNode>() {
        override fun create(): MeasureCountNode = MeasureCountNode(onMeasure)
        override fun update(node: MeasureCountNode) {
            node.onMeasure = onMeasure
        }
    }

    private class MeasureCountNode(var onMeasure: () -> Unit) :
        LayoutModifierNode, Modifier.Node() {
        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints,
        ): MeasureResult {
            onMeasure()
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
}
