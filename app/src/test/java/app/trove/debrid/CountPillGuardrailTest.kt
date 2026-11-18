package app.trove.debrid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CI guardrail for the 0.1.8–0.1.13 blank-screen loop.
 *
 * Root cause, proven on device: Material 3 1.4.0 ScrollableTabRowImpl.measure
 * queries a tab's intrinsics AFTER measuring it, which requests a remeasure.
 * CountPill used requiredWidth(IntrinsicSize.Min) — a nested intrinsic query
 * inside each tab — so every remeasure invalidated the next one and the
 * layout traversal never finished; the first frame never drew. The fix is
 * wrapContentWidth(unbounded = true), which keeps the pill's full width
 * without querying intrinsics.
 *
 * This test is a source-level guardrail, not a behaviour test: it fails the
 * moment the forbidden pattern returns to CountPill. The behaviour itself
 * is covered by TabRowLayoutSettlesTest (androidTest, device-run only).
 * The guardrail is deliberately narrow — only CountPill.kt, only the two
 * tokens that caused the loop — so it cannot false-positive on a legitimate
 * intrinsic use elsewhere.
 */
class CountPillGuardrailTest {

    private fun countPillSource(): File {
        // Unit tests run with the module directory as the working directory;
        // walk up so the test also works from the repo root.
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate =
                File(dir, "src/main/java/app/trove/debrid/ui/downloads/CountPill.kt")
            if (candidate.isFile) return candidate
            val appCandidate =
                File(dir, "app/src/main/java/app/trove/debrid/ui/downloads/CountPill.kt")
            if (appCandidate.isFile) return appCandidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "CountPill.kt not found below ${System.getProperty("user.dir")}; " +
                "move the file and update this guardrail instead of deleting it.",
        )
    }

    @Test fun `count pill never queries intrinsics`() {
        val source = countPillSource().readText()
        assertFalse(
            "CountPill must not use IntrinsicSize: it reintroduces the tab-row " +
                "layout loop fixed in 0.1.13 (see CountPillGuardrailTest).",
            source.contains("IntrinsicSize"),
        )
        assertFalse(
            "CountPill must not use requiredWidth: it reintroduces the tab-row " +
                "layout loop fixed in 0.1.13 (see CountPillGuardrailTest).",
            source.contains("requiredWidth"),
        )
    }
}
