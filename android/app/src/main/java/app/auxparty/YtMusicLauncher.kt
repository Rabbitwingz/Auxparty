package app.auxparty

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a specific track in whichever YouTube Music build is installed: the last-resort way to play. */
object YtMusicLauncher {

    sealed class Result {
        data class Started(val packageName: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val VIDEO_ID = Regex("^[\\w-]{6,20}$")

    /**
     * Which installed app opens music.youtube.com links. The stock build wins;
     * otherwise any renamed fork. Asking the package manager who handles the link
     * (rather than listing every package) needs no extra permission.
     */
    @Suppress("DEPRECATION")
    fun findPackage(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        val handlers = context.packageManager.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName }
            .distinct()
        return handlers.firstOrNull { it == Packages.YTM_STOCK }
            ?: handlers.firstOrNull { Packages.rank(it) == 0 }
    }

    fun playVideo(context: Context, videoId: String): Result {
        if (!VIDEO_ID.matches(videoId)) return Result.Failed("Invalid video id")
        return open(context, "https://music.youtube.com/watch?v=$videoId")
    }

    private fun open(context: Context, url: String): Result {
        val pkg = findPackage(context) ?: return Result.Failed("YouTube Music is not installed")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            // From the background, Android silently drops this unless the app holds
            // "display over other apps". There is no error to catch in that case.
            context.startActivity(intent)
            Result.Started(pkg)
        } catch (e: ActivityNotFoundException) {
            Result.Failed("$pkg can't open $url")
        } catch (e: SecurityException) {
            Result.Failed("Not allowed to open $pkg: ${e.message}")
        }
    }
}
