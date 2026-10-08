/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities - General
 * File: MapsLinkParser.kt
 * Description: Reads coordinates and a place name from Google Maps links and shared text.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object MapsLinkParser {
    data class Place(
        val latitude: Double,
        val longitude: Double,
        val name: String?,
    )

    // The !3d!4d pair is the place's own pin; the @ pair is only the map's viewport centre
    private val pinRegex = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val coordinatesRegex = Regex("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)")
    private val placePathRegex = Regex("/maps/place/([^/@?]+)")
    private val urlRegex = Regex("https?://\\S+")

    fun isShortLink(text: String): Boolean = text.contains("maps.app.goo.gl") || text.contains("goo.gl/maps")

    fun findUrl(text: String): String? = urlRegex.find(text)?.value

    fun parse(text: String): Place? {
        val match = pinRegex.find(text) ?: coordinatesRegex.find(text) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)) return null
        return Place(latitude, longitude, placeName(text))
    }

    // Shared text from Maps puts the place name on the first line, before the link
    fun placeName(text: String): String? {
        val firstLine =
            text
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !urlRegex.containsMatchIn(it) }
        if (firstLine != null) return firstLine
        val fromPath = placePathRegex.find(text)?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(fromPath, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // Resolves shared text or a link to a place, following short links and geocoding the address when the link has no coordinates
    fun resolve(
        context: Context,
        text: String,
    ): Place? {
        parse(text)?.let { return it }
        val link = findUrl(text) ?: text
        val resolved = if (isShortLink(link)) resolveShortLink(link) else link
        parse(resolved)?.let { return it }
        // Current Maps links name the place and its address but leave out the coordinates
        val address = placeName(resolved) ?: return null
        val located = geocode(context, address) ?: return null
        return located.copy(name = placeName(text) ?: address.substringBefore(','))
    }

    @Suppress("DEPRECATION")
    private fun geocode(
        context: Context,
        address: String,
    ): Place? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(context)
                .getFromLocationName(address, 1)
                ?.firstOrNull()
                ?.let { Place(it.latitude, it.longitude, null) }
        } catch (e: Exception) {
            Log.e("MapsLinkParser", "Geocoding failed", e)
            null
        }
    }

    // Short links only carry coordinates after the redirect, so this makes a network request
    fun resolveShortLink(shortUrl: String): String =
        try {
            val connection = URL(shortUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connect()
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            location ?: shortUrl
        } catch (e: Exception) {
            Log.e("MapsLinkParser", "Error resolving URL", e)
            shortUrl
        }
}
