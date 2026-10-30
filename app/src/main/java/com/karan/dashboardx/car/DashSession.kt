package com.karan.dashboardx.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One car session: a single [DashScreen]. */
class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
}
