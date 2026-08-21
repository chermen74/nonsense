package com.nonsense

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
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

        // Draw edge to edge, under status + nav bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        setContentView(NonsenseView(this))
    }
}
