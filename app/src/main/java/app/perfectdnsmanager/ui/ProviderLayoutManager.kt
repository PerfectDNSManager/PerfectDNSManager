package app.perfectdnsmanager.ui

import android.view.View
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.perfectdnsmanager.R

/** Keep remote-control vertical navigation in the selected provider/action column. */
class ProviderLayoutManager(private val list: RecyclerView) : LinearLayoutManager(list.context) {
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return null
        if (focused.id != R.id.layoutProviderButton && focused.id != R.id.tvType) return null
        val row = list.findContainingItemView(focused) ?: return null
        val position = list.getChildAdapterPosition(row)
        if (position == RecyclerView.NO_POSITION) return focused
        val next = position + if (direction == View.FOCUS_DOWN) 1 else -1
        if (next < 0) {
            return list.rootView.findViewById(
                if (focused.id == R.id.layoutProviderButton) R.id.btnSpeedtest else R.id.btnAddProfile)
        }
        if (next >= itemCount) return focused
        val column = focused.id
        findViewByPosition(next)?.findViewById<View>(column)?.let { return it }
        // The neighbour may not be attached yet. Keep focus until its row is laid out,
        // rather than letting FocusFinder jump to the other column in a visible row.
        list.doOnNextLayout {
            if (list.hasFocus()) findViewByPosition(next)?.findViewById<View>(column)?.requestFocus()
        }
        scrollToPosition(next)
        return focused
    }
}
