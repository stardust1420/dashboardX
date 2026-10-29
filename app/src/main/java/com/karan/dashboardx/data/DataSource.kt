package com.karan.dashboardx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface DataSource {
    val state: StateFlow<CarState>
    val status: StateFlow<ConnectionStatus>
    fun start(scope: CoroutineScope)
    fun stop()
}
