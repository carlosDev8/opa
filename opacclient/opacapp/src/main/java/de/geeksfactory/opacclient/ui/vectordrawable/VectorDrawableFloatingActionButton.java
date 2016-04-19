package de.geeksfactory.opacclient.ui.vectordrawable;

import android.content.Context;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.widget.AppCompatDrawableManager;
import android.support.v7.widget.AppCompatImageHelper;
import android.util.AttributeSet;

public class VectorDrawableFloatingActionButton extends FloatingActionButton {

    private AppCompatImageHelper mImageHelper;

    public VectorDrawableFloatingActionButton(Context context) {
        this(context, null);
    }

    public VectorDrawableFloatingActionButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VectorDrawableFloatingActionButton(Context context, AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final AppCompatDrawableManager drawableManager = AppCompatDrawableManager.get();
        mImageHelper = new AppCompatImageHelper(this, drawableManager);
        if (!isInEditMode()) {
            mImageHelper.loadFromAttributes(attrs, defStyleAttr);
        }
    }

}
