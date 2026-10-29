package com.karan.dashboardx.render

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface

/** The three OFL fonts, loaded from assets/fonts (no R class). Create once per host, not per frame. */
class Fonts(ctx: Context) {
    private val assets = ctx.applicationContext.assets

    /** Variable font (wght 400..900); pick the weight per Paint with [orbitron]. */
    val orbitronVar: Typeface = Typeface.createFromAsset(assets, "fonts/orbitron.ttf")
    val shareTechMono: Typeface = Typeface.createFromAsset(assets, "fonts/share_tech_mono.ttf")
    val pressStart2p: Typeface = Typeface.createFromAsset(assets, "fonts/press_start_2p.ttf")

    /** Sets Orbitron at [weight] on [p]. Call at setup time only (builds a String). */
    fun orbitron(p: Paint, weight: Int): Paint {
        p.typeface = orbitronVar
        p.setFontVariationSettings("'wght' $weight")
        return p
    }
}
