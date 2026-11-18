package app.trove.debrid.core.update

/** Public release metadata; never contains account credentials. */
data class LatestRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val size: Long,
    val tag: String = "v$versionName",
    val checksumUrl: String = "$apkUrl.sha256",
)
