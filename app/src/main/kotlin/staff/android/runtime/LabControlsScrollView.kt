package staff.android.runtime

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Leaves room for the shopper's independently scrollable product and status. */
class LabControlsScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
            else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
