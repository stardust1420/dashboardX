package com.karan.dashboardx.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The three OFL fonts, loaded from assets/fonts (no R class), the optional car photo for the
 * Luxury style and the cover art for the Most Wanted style. Create once per host, not per frame.
 */
class Fonts(ctx: Context) {
    private val assets = ctx.applicationContext.assets

    /** assets/car/verna.png (a cut-out on transparency), or null if absent or unreadable. */
    val carPhoto: Bitmap? by lazy {
        try {
            assets.open("car/verna.png").use(BitmapFactory::decodeStream)
        } catch (e: Exception) {
            null
        }
    }

    /** assets/art/most_wanted.png (the NFS: Most Wanted cover, 600×900), or null if absent or unreadable. */
    val mostWantedArt: Bitmap? by lazy {
        try {
            assets.open("art/most_wanted.png").use(BitmapFactory::decodeStream)
        } catch (e: Exception) {
            null
        }
    }

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
