package com.example.myapplication.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {
    private const val TAG = "LOCATION_HELPER"

    suspend fun getCurrentLocation(context: Context): Location? {
        return suspendCancellableCoroutine { continuation ->
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        continuation.resume(location)
                    } else {
                        // Si no hay última ubicación, pedir una nueva
                        fusedLocationClient.requestLocationUpdates(
                            com.google.android.gms.location.LocationRequest.create().apply {
                                priority = com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
                                interval = 10000
                                fastestInterval = 5000
                            },
                            object : com.google.android.gms.location.LocationCallback() {
                                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                                    val loc = result.lastLocation
                                    if (loc != null && continuation.isActive) {
                                        fusedLocationClient.removeLocationUpdates(this)
                                        continuation.resume(loc)
                                    }
                                }
                            },
                            null
                        )
                    }
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}