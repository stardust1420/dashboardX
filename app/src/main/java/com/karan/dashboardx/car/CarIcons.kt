package com.karan.dashboardx.car

import android.graphics.Bitmap
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * Action-strip icons made in code (the build has no R class). Built once on first use and
 * shared; CarIcon is immutable.
 */
internal object CarIcons {
    private const val SIZE = 96

    /**
     * A fully transparent icon for the one action NavigationTemplate insists on: the Sound toggle
     * is drawn by each style, so the strip has nothing to show.
     */
    val blank: CarIcon by lazy {
        CarIcon.Builder(IconCompat.createWithBitmap(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))).build()
    }
}
