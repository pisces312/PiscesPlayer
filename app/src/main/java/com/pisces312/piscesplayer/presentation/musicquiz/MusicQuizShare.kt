package com.pisces312.piscesplayer.presentation.musicquiz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.content.FileProvider
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizRunSummary
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizShareText
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Share-text assembly plus screenshot capture / share / clipboard plumbing for the result screen. */
internal object MusicQuizShare {

    fun buildText(context: Context, summary: MusicQuizRunSummary, isNewBestScore: Boolean): String {
        val strings = MusicQuizShareText.Strings(
            header = context.getString(R.string.music_quiz_share_header),
            recordMarker = context.getString(R.string.music_quiz_share_new_record),
            statsFormat = context.getString(R.string.music_quiz_share_stats),
            statsNoAverageFormat = context.getString(R.string.music_quiz_share_stats_no_avg),
            callToAction = context.getString(R.string.music_quiz_share_call_to_action),
        )
        return MusicQuizShareText.build(
            score = summary.score,
            correctCount = summary.correctCount,
            totalRounds = summary.totalRounds,
            maxStreak = summary.maxStreak,
            averageCorrectSeconds = if (summary.correctCount > 0) {
                summary.averageCorrectAnswerMs / 1_000f
            } else {
                null
            },
            isNewBestScore = isNewBestScore,
            strings = strings,
        )
    }

    /** Renders the recorded offscreen share card into a PNG inside cacheDir; null on failure. */
    suspend fun capturePng(context: Context, layer: GraphicsLayer): File? {
        val imageBitmap = runCatching { layer.toImageBitmap() }
            .onFailure { Timber.e(it, "Unable to render music quiz share card") }
            .getOrNull() ?: return null
        return writePng(context, imageBitmap)
    }

    private suspend fun writePng(context: Context, imageBitmap: ImageBitmap): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap: Bitmap = imageBitmap.asAndroidBitmap()
                val dir = File(context.cacheDir, "music_quiz_share").apply { mkdirs() }
                val file = File(dir, "music-quiz-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        "Bitmap.compress returned false"
                    }
                }
                file
            }.onFailure { Timber.e(it, "Unable to write music quiz share PNG") }
                .getOrNull()
        }

    fun shareImage(context: Context, png: File, shareText: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", png)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.music_quiz_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.music_quiz_share_clipboard_label), text),
        )
    }
}
