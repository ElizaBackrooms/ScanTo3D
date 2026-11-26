package com.example

import android.content.Context
import com.example.data.AppDatabase
import com.example.data.ScanRepository
import com.example.data.StudioPreferences
import com.example.scanner.HardwareCapabilities
import com.example.scanner.SensorReadiness

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val scanRepository: ScanRepository = ScanRepository(appContext, database.roomScanDao())
    val studioPreferences: StudioPreferences = StudioPreferences(appContext)
    val sensorReadiness: SensorReadiness = HardwareCapabilities.probe(appContext)
}
