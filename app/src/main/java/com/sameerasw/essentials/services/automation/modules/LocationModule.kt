/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: LocationModule.kt
 * Description: Registers a geofence for each arrive and leave location trigger.
 */

package com.sameerasw.essentials.services.automation.modules

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.sameerasw.essentials.domain.diy.Automation
import com.sameerasw.essentials.domain.diy.Trigger
import com.sameerasw.essentials.services.automation.receivers.GeofenceReceiver

class LocationModule : AutomationModule {
    companion object {
        const val ID = "location_module"
    }

    override val id: String = ID
    private var appContext: Context? = null
    // Everything a geofence is built from, so a moved pin or a new radius is registered again
    private var registered: Set<LocationTrigger> = emptySet()

    override fun start(context: Context) {
        appContext = context.applicationContext
    }

    override fun stop(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        registered = emptySet()
        appContext = null
    }

    override fun updateAutomations(automations: List<Automation>) {
        val context = appContext ?: return
        val triggers = automations.mapNotNull(::locationTriggerFor).toSet()
        // Skipping an unchanged set avoids re-registering on every unrelated automation edit
        if (triggers == registered && triggers.isNotEmpty()) return
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        registered = emptySet()
        if (triggers.isEmpty()) return
        if (!hasPermissions(context)) {
            Log.w(ID, "Location or background location permission missing; location triggers won't run")
            return
        }
        register(context, triggers)
    }

    @SuppressLint("MissingPermission")
    private fun register(
        context: Context,
        triggers: Set<LocationTrigger>,
    ) {
        val request =
            GeofencingRequest
                .Builder()
                // No initial trigger: being inside already shouldn't fire every time the service restarts
                .setInitialTrigger(0)
                .addGeofences(triggers.map { it.toGeofence() })
                .build()
        try {
            LocationServices
                .getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnSuccessListener { registered = triggers }
                .addOnFailureListener { Log.e(ID, "Failed to add geofences", it) }
        } catch (e: SecurityException) {
            Log.e(ID, "Geofence permission denied", e)
        }
    }

    private data class LocationTrigger(
        val automationId: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Int,
        val transition: Int,
    ) {
        // The request id is the automation id, so the receiver can look the automation up
        fun toGeofence(): Geofence =
            Geofence
                .Builder()
                .setRequestId(automationId)
                .setCircularRegion(latitude, longitude, radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(transition)
                .build()
    }

    private fun locationTriggerFor(automation: Automation): LocationTrigger? {
        val trigger =
            when (val t = automation.trigger) {
                is Trigger.ArriveAtLocation ->
                    LocationTrigger(automation.id, t.latitude, t.longitude, t.radiusMeters, Geofence.GEOFENCE_TRANSITION_ENTER)
                is Trigger.LeaveLocation ->
                    LocationTrigger(automation.id, t.latitude, t.longitude, t.radiusMeters, Geofence.GEOFENCE_TRANSITION_EXIT)
                else -> return null
            }
        return trigger.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 }
    }

    private fun hasPermissions(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    // Geofencing fills in the event extras, so the intent must be mutable
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
}
