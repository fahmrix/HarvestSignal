package com.example.wirelessharvester

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.wirelessharvester.data.*
import com.example.wirelessharvester.utils.HarvesterPermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class WirelessHarvester(private val context: Context) {

    companion object {
        private const val TAG = "WirelessHarvester"
        // Interval pemindaian Wi-Fi berkelanjutan (aktif bila throttling dimatikan di Developer Options)
        private const val WIFI_SCAN_LOOP_INTERVAL_MS = 2000L
        private const val GPS_MIN_TIME_MS = 1000L
        private const val GPS_MIN_DISTANCE_M = 0f
    }

    interface HarvesterListener {
        fun onSignalCaptured(signal: WirelessSignal, totalWifi: Int, totalBle: Int)
        fun onLocationUpdated(gps: GpsCoordinate)
    }

    var listener: HarvesterListener? = null

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var isHarvesting = false

    // Real-time signals cache (BSSID -> WirelessSignal)
    private val activeSignals = ConcurrentHashMap<String, WirelessSignal>()
    private val historicalSnapshots = CopyOnWriteArrayList<WirelessSnapshot>()
    private var lastKnownGps: GpsCoordinate? = null

    // Handler untuk loop pemindaian Wi-Fi secara kontinu
    private val scanLoopHandler = Handler(Looper.getMainLooper())
    private val wifiLoopRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!isHarvesting) return
            try {
                // Di Android 9 (API 28)+, pemanggilan startScan() dibatasi 4x per 2 menit untuk foreground app.
                // Jika opsi "Wi-Fi scan throttling" dimatikan di Developer Options,
                // pemanggilan per 2 detik ini akan selalu diizinkan oleh OS.
                val initiated = wifiManager.startScan()
                if (!initiated) {
                    Log.w(TAG, "Wi-Fi startScan() ditolak/dibatasi oleh framework Android.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memicu Wi-Fi scan: ${e.message}")
            }
            scanLoopHandler.postDelayed(this, WIFI_SCAN_LOOP_INTERVAL_MS)
        }
    }

    // --- Wi-Fi Scan Broadcast Receiver ---
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return

            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                val results = wifiManager.scanResults
                val now = System.currentTimeMillis()
                for (scan in results) {
                    val bssid = scan.BSSID ?: continue
                    val signal = WirelessSignal(
                        type = SignalType.WIFI,
                        ssid = scan.SSID,
                        bssid = bssid,
                        rssi = scan.level,
                        frequencyMhz = scan.frequency,
                        timestampMs = now,
                        location = lastKnownGps
                    )
                    activeSignals[bssid] = signal
                    notifySignalCaptured(signal)
                }
            }
        }
    }

    // --- Bluetooth LE Scan Callback ---
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleBleResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleBleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan gagal, kode error: $errorCode")
        }

        @SuppressLint("MissingPermission")
        private fun handleBleResult(result: ScanResult) {
            val device = result.device ?: return
            val bssid = device.address ?: return
            val now = System.currentTimeMillis()

            val signal = WirelessSignal(
                type = SignalType.BLE,
                ssid = device.name,
                bssid = bssid,
                rssi = result.rssi,
                frequencyMhz = null,
                timestampMs = now,
                location = lastKnownGps
            )
            activeSignals[bssid] = signal
            notifySignalCaptured(signal)
        }
    }

    // --- GPS Location Listener ---
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val gps = GpsCoordinate(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracyMeters = location.accuracy,
                timestampMs = location.time
            )
            lastKnownGps = gps
            Handler(Looper.getMainLooper()).post {
                listener?.onLocationUpdated(gps)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun notifySignalCaptured(signal: WirelessSignal) {
        val totalWifi = activeSignals.values.count { it.type == SignalType.WIFI }
        val totalBle = activeSignals.values.count { it.type == SignalType.BLE }
        Handler(Looper.getMainLooper()).post {
            listener?.onSignalCaptured(signal, totalWifi, totalBle)
        }
    }

    // --- Public Methods ---

    /**
     * Memulai pemanenan sinyal nirkabel dan lokasi secara kontinu.
     */
    @SuppressLint("MissingPermission")
    @Throws(SecurityException::class)
    fun startHarvesting() {
        if (isHarvesting) return

        if (!HarvesterPermissions.hasAllPermissions(context)) {
            throw SecurityException("Izin runtime belum lengkap untuk melakukan harvesting sinyal.")
        }

        isHarvesting = true
        activeSignals.clear()

        // 1. Mengaktifkan GPS updates
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_MIN_TIME_MS,
                    GPS_MIN_DISTANCE_M,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    GPS_MIN_TIME_MS,
                    GPS_MIN_DISTANCE_M,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mengaktifkan LocationManager: ${e.message}")
        }

        // 2. Mendaftarkan Wi-Fi Broadcast Receiver & Memulai Looper
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, filter)
        scanLoopHandler.post(wifiLoopRunnable)

        // 3. Memulai BLE Continuous Scan dengan Low Latency
        val bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothAdapter?.isEnabled == true && bleScanner != null) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            bleScanner.startScan(null, settings, bleScanCallback)
            Log.d(TAG, "BLE Scanner aktif (SCAN_MODE_LOW_LATENCY).")
        } else {
            Log.w(TAG, "Bluetooth tidak aktif atau perangkat tidak mendukung BLE.")
        }

        Log.i(TAG, "Continuous Wireless Harvester berhasil dijalankan.")
    }

    /**
     * Menghentikan semua proses pemindaian dan melepaskan listener.
     */
    @SuppressLint("MissingPermission")
    fun stopHarvesting() {
        if (!isHarvesting) return
        isHarvesting = false

        // Hentikan Wi-Fi Looper & unregister receiver
        scanLoopHandler.removeCallbacks(wifiLoopRunnable)
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver belum terdaftar
        }

        // Hentikan BLE Scan
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)
        }

        // Hentikan GPS updates
        locationManager.removeUpdates(gpsListener)

        Log.i(TAG, "Continuous Wireless Harvester dihentikan.")
    }

    /**
     * Mengambil snapshot sinyal saat ini yang dipanen dan menyimpannya ke riwayat.
     */
    fun captureCurrentSnapshot(): WirelessSnapshot {
        val captured = activeSignals.values.toList()
        val snapshot = WirelessSnapshot(
            snapshotTimestamp = System.currentTimeMillis(),
            location = lastKnownGps,
            signals = captured
        )
        historicalSnapshots.add(snapshot)
        return snapshot
    }

    /**
     * Mengekspor seluruh snapshot yang telah dipanen ke dalam format JSON String.
     */
    fun exportSnapshotsToJson(): String {
        val jsonArray = org.json.JSONArray()
        historicalSnapshots.forEach { snapshot ->
            jsonArray.put(org.json.JSONObject(snapshot.toJson()))
        }
        return jsonArray.toString()
    }

    fun getHistoricalSnapshots(): List<WirelessSnapshot> = historicalSnapshots.toList()

    fun getActiveSignalsCount(): Pair<Int, Int> {
        val wifiCount = activeSignals.values.count { it.type == SignalType.WIFI }
        val bleCount = activeSignals.values.count { it.type == SignalType.BLE }
        return Pair(wifiCount, bleCount)
    }

    fun clearHistory() {
        historicalSnapshots.clear()
        activeSignals.clear()
    }

    fun isHarvesting(): Boolean = isHarvesting
}
