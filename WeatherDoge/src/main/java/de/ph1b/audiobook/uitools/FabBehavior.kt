package de.ph1b.audiobook.uitools

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.getbase.floatingactionbutton.FloatingActionsMenu
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs
import kotlin.math.min

class FabBehavior @JvmOverloads constructor(
    context: Context? = null,
    attrs: AttributeSet? = null
) : CoordinatorLayout.Behavior<FloatingActionsMenu>(context, attrs) {

    private var mTranslationY = 0f

    override fun layoutDependsOn(parent: CoordinatorLayout, child: FloatingActionsMenu, dependency: View): Boolean {
        return dependency is Snackbar.SnackbarLayout
    }

    override fun onDependentViewChanged(parent: CoordinatorLayout, child: FloatingActionsMenu, dependency: View): Boolean {
        if (dependency is Snackbar.SnackbarLayout) {
            updateFabTranslationForSnackbar(parent, child, dependency)
        }
        return false
    }

    private fun updateFabTranslationForSnackbar(parent: CoordinatorLayout, fab: FloatingActionsMenu, snackbar: View) {
        val translationY = getFabTranslationYForSnackbar(parent, fab)
        if (translationY != mTranslationY) {
            ViewCompat.animate(fab).cancel()
            if (abs(translationY - mTranslationY) == snackbar.height.toFloat()) {
                ViewCompat.animate(fab)
                    .translationY(translationY)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .setListener(null)
            } else {
                ViewCompat.setTranslationY(fab, translationY)
            }
            mTranslationY = translationY
        }
    }

    companion object {
        private fun getFabTranslationYForSnackbar(parent: CoordinatorLayout, fab: FloatingActionsMenu): Float {
            var minOffset = 0.0f
            val dependencies = parent.getDependencies(fab)
            for (view in dependencies) {
                if (view is Snackbar.SnackbarLayout && parent.doViewsOverlap(fab, view)) {
                    minOffset = min(minOffset, ViewCompat.getTranslationY(view) - view.height.toFloat())
                }
            }
            return minOffset
        }
    }
}
