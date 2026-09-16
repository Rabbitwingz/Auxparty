package app.musicremote

/**
 * Recognises media apps by the shape of their package name rather than an exact
 * list. Patched builds (Morphe, ReVanced) rename themselves — e.g.
 * app.morphe.android.apps.youtube.music — and must still be recognised.
 */
object Packages {
    const val YTM_STOCK = "com.google.android.apps.youtube.music"

    /** Lower is more preferred. */
    fun rank(pkg: String?): Int {
        val p = pkg?.lowercase() ?: return 9
        return when {
            p.endsWith("youtube.music") -> 0
            "plex" in p -> 1
            p.endsWith("youtube") -> 2
            "spotify" in p -> 3
            else -> 9
        }
    }

    fun label(pkg: String?): String = when (rank(pkg)) {
        0 -> "YouTube Music"
        1 -> "Plex"
        2 -> "YouTube"
        3 -> "Spotify"
        else -> pkg?.substringAfterLast('.') ?: ""
    }
}
