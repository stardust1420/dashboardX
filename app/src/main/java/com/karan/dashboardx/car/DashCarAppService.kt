package com.karan.dashboardx.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Android Auto entry point, registered as a NAVIGATION app so the host hands us a raw Surface. */
class DashCarAppService : CarAppService() {
    /** Personal sideload: accept any host (Android Auto, the DHU). */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}
