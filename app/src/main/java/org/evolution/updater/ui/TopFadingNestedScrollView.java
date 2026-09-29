package org.evolution.updater.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.core.widget.NestedScrollView;

/** NestedScrollView chi fade canh tren; canh duoi dung overlay activity. */
public class TopFadingNestedScrollView extends NestedScrollView {

    public TopFadingNestedScrollView(Context context) {
        super(context);
    }

    public TopFadingNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TopFadingNestedScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return super.getTopFadingEdgeStrength();
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f; // khong fade day — overlay activity lo
    }
}
