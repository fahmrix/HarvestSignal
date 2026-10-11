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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import com.example.wirelessharvester.data.*
import com.example.wirelessharvester.utils.HarvesterPermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class WirelessHarvester(private val context: Context) {

    companion object {
        private const val TAG = "WirelessHarvester"
        // Interval pemindaian kontinu (aktif jika Wi-Fi throttling dimatikan di Developer Options)
        private const val HARVEST_LOOP_INTERVAL_MS = 2000L
        private const val GPS_MIN_TIME_MS = 1000L
        private const val GPS_MIN_DISTANCE_M = 0f
    }

    interface HarvesterListener {
        fun onSignalCaptured(signal: WirelessSignal, totalWifi: Int, totalBle: Int, totalCellular: Int)
        fun onLocationUpdated(gps: GpsCoordinate)
    }

    var listener: HarvesterListener? = null

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Volatile
    private var isHarvesting = false

    // Real-time signals cache (Key -> WirelessSignal)
    private val activeSignals = ConcurrentHashMap<String, WirelessSignal>()
    private val historicalSnapshots = CopyOnWriteArrayList<WirelessSnapshot>()
    private var lastKnownGps: GpsCoordinate? = null

    // Handler untuk loop pemindaian kontinu (Wi-Fi & Jaringan Seluler)
    private val scanLoopHandler = Handler(Looper.getMainLooper())
    private val harvestLoopRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!isHarvesting) return

            // 1. Trigger Wi-Fi Scanning
            try {
                val initiated = wifiManager.startScan()
                if (!initiated) {
                    Log.w(TAG, "Wi-Fi startScan() ditolak/dibatasi oleh framework Android.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memicu Wi-Fi scan: ${e.message}")
            }

            // 2. Trigger Telephony BTS Scanning (Serving & Neighboring Cells)
            try {
                harvestCellularInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memanen Telephony CellInfo: ${e.message}")
            }

            scanLoopHandler.postDelayed(this, HARVEST_LOOP_INTERVAL_MS)
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

    // --- Telephony / Cellular Harvesting Logic ---
    @SuppressLint("MissingPermission")
    private fun harvestCellularInfo() {
        val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo
        if (cellInfoList.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        val operatorName = telephonyManager.networkOperatorName.ifEmpty { "Cellular" }

        for (cell in cellInfoList) {
            val signal = parseCellInfo(cell, now, operatorName) ?: continue
            activeSignals[signal.bssid] = signal
            notifySignalCaptured(signal)
        }
    }

    @SuppressLint("NewApi")
    private fun parseCellInfo(cell: CellInfo, timestampMs: Long, operatorName: String): WirelessSignal? {
        val isRegistered = cell.isRegistered
        val statusStr = if (isRegistered) "Registered" else "Unregistered"

        return when {
            // 4G LTE
            cell is CellInfoLte -> {
                val identity = cell.cellIdentity
                val strength = cell.cellSignalStrength
                val mcc = identity.mccString ?: identity.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                val mnc = identity.mncString ?: identity.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                val ci = identity.ci.toLong().takeIf { it != Int.MAX_VALUE.toLong() && it != -1L }
                val tac = identity.tac.takeIf { it != Int.MAX_VALUE && it != -1 }
                val pci = identity.pci.takeIf { it != Int.MAX_VALUE && it != -1 }
                val rsrp = strength.rsrp.takeIf { it != Int.MAX_VALUE }
                val rsrq = strength.rsrq.takeIf { it != Int.MAX_VALUE }
                val cqi = strength.cqi.takeIf { it != Int.MAX_VALUE }
                val earfcn = identity.earfcn.takeIf { it != Int.MAX_VALUE }
                val dbm = strength.dbm

                val cellKey = "LTE_${mcc ?: "000"}_${mnc ?: "00"}_${tac ?: 0}_${ci ?: pci ?: cell.hashCode()}"

                WirelessSignal(
                    type = SignalType.CELLULAR,
                    ssid = "$operatorName (LTE)",
                    bssid = cellKey,
                    rssi = dbm,
                    frequencyMhz = earfcn,
                    timestampMs = timestampMs,
                    location = lastKnownGps,
                    connectionStatus = statusStr,
                    radioType = "LTE",
                    cellId = ci,
                    tacOrLac = tac,
                    mcc = mcc,
                    mnc = mnc,
                    pci = pci,
                    rsrp = rsrp,
                    rsrq = rsrq,
                    cqi = cqi
                )
            }

            // 5G NR (New Radio) - Android 10+ (API 29+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is android.telephony.CellInfoNr -> {
                val identity = cell.cellIdentity as? android.telephony.CellIdentityNr
                val strength = cell.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                val mcc = identity?.mccString
                val mnc = identity?.mncString
                val nci = identity?.nci?.takeIf { it != CellInfo.UNAVAILABLE_LONG && it != -1L }
                val tac = identity?.tac?.takeIf { it != CellInfo.UNAVAILABLE && it != -1 }
                val pci = identity?.pci?.takeIf { it != CellInfo.UNAVAILABLE && it != -1 }
                val ssRsrp = strength?.ssRsrp?.takeIf { it != CellInfo.UNAVAILABLE }
                val ssRsrq = strength?.ssRsrq?.takeIf { it != CellInfo.UNAVAILABLE }
                val ssSinr = strength?.ssSinr?.takeIf { it != CellInfo.UNAVAILABLE }
                val nrarfcn = identity?.nrarfcn?.takeIf { it != CellInfo.UNAVAILABLE }
                val dbm = strength?.dbm ?: ssRsrp ?: -999

                val cellKey = "NR5G_${mcc ?: "000"}_${mnc ?: "00"}_${tac ?: 0}_${nci ?: pci ?: cell.hashCode()}"

                WirelessSignal(
                    type = SignalType.CELLULAR,
                    ssid = "$operatorName (5G NR)",
                    bssid = cellKey,
                    rssi = dbm,
                    frequencyMhz = nrarfcn,
                    timestampMs = timestampMs,
                    location = lastKnownGps,
                    connectionStatus = statusStr,
                    radioType = "NR_5G",
                    cellId = nci,
                    tacOrLac = tac,
                    mcc = mcc,
                    mnc = mnc,
                    pci = pci,
                    ssRsrp = ssRsrp,
                    ssRsrq = ssRsrq,
                    ssSinr = ssSinr
                )
            }

            // 2G GSM
            cell is CellInfoGsm -> {
                val identity = cell.cellIdentity
                val strength = cell.cellSignalStrength
                val mcc = identity.mccString ?: identity.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                val mnc = identity.mncString ?: identity.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                val cid = identity.cid.toLong().takeIf { it != Int.MAX_VALUE.toLong() && it != -1L }
                val lac = identity.lac.takeIf { it != Int.MAX_VALUE && it != -1 }
                val arfcn = identity.arfcn.takeIf { it != Int.MAX_VALUE }
                val dbm = strength.dbm

                val cellKey = "GSM_${mcc ?: "000"}_${mnc ?: "00"}_${lac ?: 0}_${cid ?: cell.hashCode()}"

                WirelessSignal(
                    type = SignalType.CELLULAR,
                    ssid = "$operatorName (GSM)",
                    bssid = cellKey,
                    rssi = dbm,
                    frequencyMhz = arfcn,
                    timestampMs = timestampMs,
                    location = lastKnownGps,
                    connectionStatus = statusStr,
                    radioType = "GSM",
                    cellId = cid,
                    tacOrLac = lac,
                    mcc = mcc,
                    mnc = mnc
                )
            }

            // 3G WCDMA
            cell is CellInfoWcdma -> {
                val identity = cell.cellIdentity
                val strength = cell.cellSignalStrength
                val mcc = identity.mccString ?: identity.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                val mnc = identity.mncString ?: identity.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                val cid = identity.cid.toLong().takeIf { it != Int.MAX_VALUE.toLong() && it != -1L }
                val lac = identity.lac.takeIf { it != Int.MAX_VALUE && it != -1 }
                val uarfcn = identity.uarfcn.takeIf { it != Int.MAX_VALUE }
                val psc = identity.psc.takeIf { it != Int.MAX_VALUE && it != -1 }
                val dbm = strength.dbm

                val cellKey = "WCDMA_${mcc ?: "000"}_${mnc ?: "00"}_${lac ?: 0}_${cid ?: psc ?: cell.hashCode()}"

                WirelessSignal(
                    type = SignalType.CELLULAR,
                    ssid = "$operatorName (WCDMA)",
                    bssid = cellKey,
                    rssi = dbm,
                    frequencyMhz = uarfcn,
                    timestampMs = timestampMs,
                    location = lastKnownGps,
                    connectionStatus = statusStr,
                    radioType = "WCDMA",
                    cellId = cid,
                    tacOrLac = lac,
                    mcc = mcc,
                    mnc = mnc,
                    pci = psc
                )
            }

            else -> null
        }
    }

    private fun notifySignalCaptured(signal: WirelessSignal) {
        val totalWifi = activeSignals.values.count { it.type == SignalType.WIFI }
        val totalBle = activeSignals.values.count { it.type == SignalType.BLE }
        val totalCellular = activeSignals.values.count { it.type == SignalType.CELLULAR }
        Handler(Looper.getMainLooper()).post {
            listener?.onSignalCaptured(signal, totalWifi, totalBle, totalCellular)
        }
    }

    // --- Public Methods ---

    /**
     * Memulai pemanenan sinyal nirkabel (Wi-Fi, BLE, Telephony) dan lokasi secara kontinu.
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

        // 2. Mendaftarkan Wi-Fi Broadcast Receiver & Memulai Loop Pemanenan (Wi-Fi + BTS)
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, filter)
        scanLoopHandler.post(harvestLoopRunnable)

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

        Log.i(TAG, "Continuous Wireless & Telephony Harvester berhasil dijalankan.")
    }

    /**
     * Menghentikan semua proses pemindaian dan melepaskan listener.
     */
    @SuppressLint("MissingPermission")
    fun stopHarvesting() {
        if (!isHarvesting) return
        isHarvesting = false

        // Hentikan Loop & unregister Wi-Fi receiver
        scanLoopHandler.removeCallbacks(harvestLoopRunnable)
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

        Log.i(TAG, "Continuous Wireless & Telephony Harvester dihentikan.")
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

    fun getActiveSignalsCount(): Triple<Int, Int, Int> {
        val wifiCount = activeSignals.values.count { it.type == SignalType.WIFI }
        val bleCount = activeSignals.values.count { it.type == SignalType.BLE }
        val cellCount = activeSignals.values.count { it.type == SignalType.CELLULAR }
        return Triple(wifiCount, bleCount, cellCount)
    }

    fun clearHistory() {
        historicalSnapshots.clear()
        activeSignals.clear()
    }

    fun isHarvesting(): Boolean = isHarvesting
}
