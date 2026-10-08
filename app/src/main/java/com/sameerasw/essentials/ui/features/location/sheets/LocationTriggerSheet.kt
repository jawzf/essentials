/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Location Alarms & Geofencing
 * File: LocationTriggerSheet.kt
 * Description: Picks the place and distance for arrive and leave location triggers from a Google Maps link.
 */

package com.sameerasw.essentials.ui.features.location.sheets

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.diy.Trigger
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.MapsLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationTriggerSheet(
    title: String,
    initialLatitude: Double,
    initialLongitude: Double,
    initialRadiusMeters: Int,
    initialPlaceName: String,
    onDismiss: () -> Unit,
    onSave: (latitude: Double, longitude: Double, radiusMeters: Int, placeName: String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var latitude by remember { mutableStateOf(initialLatitude) }
    var longitude by remember { mutableStateOf(initialLongitude) }
    var radiusMeters by remember { mutableIntStateOf(initialRadiusMeters) }
    var placeName by remember { mutableStateOf(initialPlaceName) }
    var isResolving by remember { mutableStateOf(false) }
    var lastHandledLink by remember { mutableStateOf<String?>(null) }
    var wentToMaps by remember { mutableStateOf(false) }
    var isAccuracyOff by remember { mutableStateOf(false) }
    val hasPlace = latitude != 0.0 || longitude != 0.0

    fun applyLink(
        text: String,
        fromClipboard: Boolean,
    ) {
        val link = MapsLinkParser.findUrl(text) ?: text
        if (link == lastHandledLink) return
        lastHandledLink = link
        scope.launch {
            isResolving = true
            val place = withContext(Dispatchers.IO) { MapsLinkParser.resolve(context, text) }
            isResolving = false
            if (place == null) {
                // A copied link that isn't a place is ignored quietly; an explicit paste gets told why
                if (!fromClipboard) Toast.makeText(context, R.string.diy_location_link_invalid, Toast.LENGTH_SHORT).show()
                return@launch
            }
            latitude = place.latitude
            longitude = place.longitude
            // The name from shared text beats one taken from the link
            place.name?.let { name -> if (placeName.isBlank() || fromClipboard) placeName = name }
            if (fromClipboard) Toast.makeText(context, R.string.diy_location_link_added, Toast.LENGTH_SHORT).show()
        }
    }

    // Coming back from Google Maps with a copied link fills the place in; the clipboard is only readable once focused.
    // A place that's already set is only replaced after going to Maps, so an old copied link can't overwrite it
    LifecycleResumeEffect(Unit) {
        isAccuracyOff = !isNetworkLocationEnabled(context)
        val job =
            scope.launch {
                delay(CLIPBOARD_READ_DELAY_MS)
                if ((latitude != 0.0 || longitude != 0.0) && !wentToMaps) return@launch
                readClipboard(context)?.takeIf { MapsLinkParser.findUrl(it)?.let(::isMapsLink) == true }?.let {
                    applyLink(it, fromClipboard = true)
                }
            }
        onPauseOrDispose { job.cancel() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Play services accepts geofences without it but never fires them
            if (isAccuracyOff) {
                RoundedCardContainer {
                    Text(
                        text = stringResource(R.string.diy_location_accuracy_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            RoundedCardContainer {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (hasPlace) placeName.ifBlank { stringResource(R.string.diy_location_pinned) } else stringResource(R.string.diy_location_none),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text =
                            if (hasPlace) {
                                String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
                            } else {
                                stringResource(R.string.diy_location_hint)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isResolving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        wentToMaps = true
                        openGoogleMaps(context, latitude, longitude, hasPlace)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(painterResource(R.drawable.rounded_map_24), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.diy_location_open_maps))
                }
                OutlinedButton(
                    onClick = {
                        HapticUtil.performUIHaptic(view)
                        val text = readClipboard(context)
                        if (text.isNullOrBlank()) {
                            Toast.makeText(context, R.string.diy_location_link_invalid, Toast.LENGTH_SHORT).show()
                        } else {
                            lastHandledLink = null
                            applyLink(text, fromClipboard = false)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(painterResource(R.drawable.rounded_content_paste_24), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.diy_location_paste_link))
                }
            }

            OutlinedTextField(
                value = placeName,
                onValueChange = { placeName = it },
                label = { Text(stringResource(R.string.diy_location_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            RoundedCardContainer {
                ConfigSliderItem(
                    title = stringResource(R.string.diy_location_distance_title),
                    value = radiusMeters.toFloat(),
                    onValueChange = { radiusMeters = it.toInt() },
                    valueRange = Trigger.MIN_LOCATION_RADIUS_METERS.toFloat()..Trigger.MAX_LOCATION_RADIUS_METERS.toFloat(),
                    increment = RADIUS_STEP_METERS.toFloat(),
                    steps = (Trigger.MAX_LOCATION_RADIUS_METERS - Trigger.MIN_LOCATION_RADIUS_METERS) / RADIUS_STEP_METERS - 1,
                    valueFormatter = { formatDistance(context, it.toInt()) },
                    iconRes = R.drawable.rounded_location_on_24,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Icon(painterResource(R.drawable.rounded_close_24), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_cancel))
                }

                Button(
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        onSave(latitude, longitude, radiusMeters, placeName.trim())
                    },
                    modifier = Modifier.weight(1f),
                    enabled = hasPlace && !isResolving,
                ) {
                    Icon(painterResource(R.drawable.rounded_check_24), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

// Google Location Accuracy is what enables the network provider
private fun isNetworkLocationEnabled(context: Context): Boolean =
    runCatching {
        context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }.getOrDefault(true)

private fun isMapsLink(url: String): Boolean =
    MapsLinkParser.isShortLink(url) || url.contains("google.com/maps") || url.contains("maps.google.com")

private fun readClipboard(context: Context): String? =
    runCatching {
        context
            .getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }.getOrNull()

private fun openGoogleMaps(
    context: Context,
    latitude: Double,
    longitude: Double,
    hasPlace: Boolean,
) {
    val uri =
        if (hasPlace) {
            Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
        } else {
            Uri.parse("https://www.google.com/maps")
        }
    val maps = Intent(Intent.ACTION_VIEW, uri).setPackage(GOOGLE_MAPS_PACKAGE)
    try {
        context.startActivity(maps)
    } catch (_: Exception) {
        // Without the Maps app, the web version still gives a copyable link
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}

private fun formatDistance(
    context: Context,
    meters: Int,
): String =
    if (meters >= 1000) {
        context.getString(R.string.diy_location_distance_km, meters / 1000f)
    } else {
        context.getString(R.string.diy_location_distance_m, meters)
    }

private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
private const val RADIUS_STEP_METERS = 100
private const val CLIPBOARD_READ_DELAY_MS = 400L
