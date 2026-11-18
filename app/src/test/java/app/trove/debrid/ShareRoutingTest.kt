package app.trove.debrid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Routing guard only; chooser, URI grants and window behavior still need a phone. */
class ShareRoutingTest {
    @Test fun `launcher and share intents belong to separate activities`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        val byName = (0 until activities.length).map { activities.item(it) as Element }
            .associateBy { it.getAttributeNS(android, "name") }
        fun actions(activity: Element): List<String> {
            val nodes = activity.getElementsByTagName("action")
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
        }
        assertEquals(listOf("android.intent.action.MAIN"), actions(byName.getValue(".MainActivity")))
        val share = byName.getValue(".ShareActivity")
        assertEquals("true", share.getAttributeNS(android, "excludeFromRecents"))
        assertEquals(setOf("android.intent.action.SEND", "android.intent.action.VIEW"), actions(share).toSet())
        val source = File(root, "app/src/main/java/app/trove/debrid/ShareActivity.kt").readText()
        assertTrue(source.contains("AddSheet("))
        assertFalse(source.contains("DownloadsScreen("))
        assertFalse(source.contains("NavHost("))
    }
}
