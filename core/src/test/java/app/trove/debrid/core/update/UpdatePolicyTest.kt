package app.trove.debrid.core.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun requiresExactAssetPath() {
        val name = "Trove-v0.1.18.apk"
        val url = "${UpdatePolicy.RELEASES}/download/v0.1.18/$name"
        assertTrue(UpdatePolicy.isAssetUrl(url, "v0.1.18", name))
        for (bad in listOf("$url?x=1", "$url#apk", url.replace("github.com", "github.com:443"),
            url.replace("/bitmere/", "/%62itmere/"), url.replace("/trove/", "/other/"), url.lowercase())) {
            assertFalse(bad, UpdatePolicy.isAssetUrl(bad, "v0.1.18", name))
        }
    }

    @Test fun redirectsRequireExactCdnOrigin() {
        val cdn = "https://release-assets.githubusercontent.com/assets/apk?token=fixture"
        assertTrue(UpdatePolicy.isReleaseRedirect(cdn))
        for (bad in listOf("$cdn#x", cdn.replace("https:", "http:"), cdn.replace(".com", ".com.invalid"),
            cdn.replace(".com", ".com:8443"), cdn.replace(".com", ".com:"), cdn.replace("://", "://fixture@"),
            cdn.replace("release-assets", "objects"))) assertFalse(bad, UpdatePolicy.isReleaseRedirect(bad))
    }

    @Test fun comparesVersionCodesAndPackage() {
        assertTrue(UpdatePolicy.isNewer(21, 20))
        assertFalse(UpdatePolicy.isNewer(20, 20))
        assertFalse(UpdatePolicy.isNewer(19, 20))
        assertTrue(UpdatePolicy.isEligible(UpdatePolicy.PACKAGE_NAME, 21, 21, 20))
        assertFalse(UpdatePolicy.isEligible("other", 21, 21, 20))
        assertFalse(UpdatePolicy.isEligible(UpdatePolicy.PACKAGE_NAME, 22, 21, 20))
    }
}
