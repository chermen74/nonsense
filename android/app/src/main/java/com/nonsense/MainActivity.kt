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

    private lateinit var view: NonsenseView
    private var billing: Billing? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge to edge so the tint covers the whole screen, but keep the
        // real insets coming: FLAG_LAYOUT_NO_LIMITS put the view under the
        // navigation bar and reported nothing, which left the controls beneath
        // the gesture pill where the system ate every tap.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        view = NonsenseView(this)
        setContentView(view)

        // The activity is the only thing that has to know a store exists: the
        // view asks to buy, and is told later what happened. Billing needs an
        // Activity to launch its flow, which is the whole reason the wiring
        // lives up here rather than in the view.
        val store = Billing(this) { tier, price -> view.applyTier(tier, price) }
        billing = store
        view.onBuy = { store.buy(this) }
        view.onRestore = { store.restore() }
        store.start()
    }

    override fun onResume() {
        super.onResume()
        // A purchase can complete outside the app — bought on another device,
        // or refunded. Ask again every time we come back.
        billing?.restore()
    }

    override fun onDestroy() {
        billing?.stop()
        super.onDestroy()
    }
}
