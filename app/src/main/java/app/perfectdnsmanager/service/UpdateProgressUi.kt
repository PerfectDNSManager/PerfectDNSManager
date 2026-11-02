package app.perfectdnsmanager.service

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.perfectdnsmanager.R
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-bound progress: cancellation also cancels the real HTTP call. */
internal class UpdateProgressUi(private val activity: Activity, private val cancelCall: () -> Unit) : DefaultLifecycleObserver {
    val cancelled = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(activity).apply { textSize = 18f; text = activity.getString(R.string.update_connecting) }
    private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
    private val details = TextView(activity).apply { textSize = 15f }
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt(); setPadding(pad,pad,pad,pad)
        addView(status); addView(bar, LinearLayout.LayoutParams(-1,(24 * resources.displayMetrics.density).toInt()))
        addView(details)
    }
    private val dialog = AlertDialog.Builder(activity).setTitle(R.string.update_dialog_title)
        .setView(body).setNegativeButton(R.string.cancel,null).setCancelable(false).create()
    private var lastProgress = 0L
    private var closed = false
    fun show() {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
        dialog.setOnDismissListener {
            closed=true
            (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            requestFocus()
            setOnClickListener {
                cancelled.set(true); cancelCall(); isEnabled=false
                status.setText(R.string.cancelling)
            }
        }
    }
    fun progress(bytes: Long, total: Long) {
        val now=SystemClock.elapsedRealtime()
        if(bytes != 0L && now-lastProgress<200) return
        lastProgress=now
        main {
            if(cancelled.get())return@main
            status.setText(R.string.update_downloading)
            bar.isIndeterminate=total<=0
            if(total>0) {
                val percent=((bytes*100/total).coerceIn(0,99)).toInt()
                bar.progress=percent
                details.text=activity.getString(R.string.update_progress_amount,percent,
                    Formatter.formatShortFileSize(activity,bytes),Formatter.formatShortFileSize(activity,total))
            } else details.text=Formatter.formatShortFileSize(activity,bytes)
        }
    }
    fun stage(label: Int) = main {
        status.setText(label); details.text=""; bar.isIndeterminate=true
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=false
    }
    fun error(message: String) = main {
        status.text=message; details.text=""; bar.visibility=View.GONE
        dialog.setCancelable(true)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            text=activity.getString(android.R.string.ok); isEnabled=true
            setOnClickListener { close() }; requestFocus()
        }
    }
    fun close() {
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post{close()};return}
        closed=true; dialog.dismiss()
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
    }
    private fun main(action: ()->Unit) {
        handler.post { if(!closed&&!activity.isFinishing&&!activity.isDestroyed)action() }
    }
    override fun onDestroy(owner: LifecycleOwner) { cancelled.set(true);cancelCall();close() }
}
