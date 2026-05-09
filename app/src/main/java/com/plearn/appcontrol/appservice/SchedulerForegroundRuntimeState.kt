package com.plearn.appcontrol.appservice

object SchedulerForegroundRuntimeState {
    @Volatile
    var running: Boolean = false
}