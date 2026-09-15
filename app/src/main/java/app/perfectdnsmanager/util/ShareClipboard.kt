package app.perfectdnsmanager.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Called only after an explicit Copy button press; never reads the clipboard. */
object ShareClipboard {
    fun copy(context: Context, text: String) {
        val clip = ClipData.newPlainText("PDM Share", text)
        if (Build.VERSION.SDK_INT >= 24) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}
