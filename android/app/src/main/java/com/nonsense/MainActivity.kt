package com.nonsense

import android.app.Activity
import android.os.Bundle
import androidx.core.view.WindowCompat

/**
 * Nonsense — a sheer full-screen surface over whatever was on screen.
 *
 * The activity theme is translucent, so the launcher (and incoming
 * notification banners) remain visible underneath. While the app is
 * foregrounded, every touch belongs to the toy. Back gesture /
 * home exits like any normal app.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge to edge so the tint covers the whole screen, but keep the
        // real insets coming: FLAG_LAYOUT_NO_LIMITS put the view under the
        // navigation bar and reported nothing, which left the controls beneath
        // the gesture pill where the system ate every tap.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(NonsenseView(this))
    }
}
