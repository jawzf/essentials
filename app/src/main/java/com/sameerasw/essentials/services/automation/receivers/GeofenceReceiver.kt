/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: GeofenceReceiver.kt
 * Description: Runs arrive and leave location automations when Play services reports a geofence transition.
 */

package com.sameerasw.essentials.services.automation.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.sameerasw.essentials.domain.diy.Automation
import com.sameerasw.essentials.domain.diy.DIYRepository
import com.sameerasw.essentials.domain.diy.Trigger
import com.sameerasw.essentials.services.automation.executors.CombinedActionExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error ${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet()
        if (ids.isEmpty()) return

        // The process may have been started just for this broadcast
        DIYRepository.init(context.applicationContext)
        val automations =
            DIYRepository.automations.value.filter { automation ->
                automation.isEnabled &&
                    automation.type == Automation.Type.TRIGGER &&
                    automation.id in ids &&
                    when (automation.trigger) {
                        is Trigger.ArriveAtLocation -> transition == Geofence.GEOFENCE_TRANSITION_ENTER
                        is Trigger.LeaveLocation -> transition == Geofence.GEOFENCE_TRANSITION_EXIT
                        else -> false
                    }
            }
        if (automations.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                automations.forEach { CombinedActionExecutor.executeAll(context.applicationContext, it.actionList) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
