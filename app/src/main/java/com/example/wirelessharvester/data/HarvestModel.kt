package com.example.wirelessharvester.data

import org.json.JSONArray
import org.json.JSONObject

enum class SignalType { WIFI, BLE, CELLULAR }

data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("lat", latitude)
        put("lng", longitude)
        put("alt", altitude)
        put("accuracy", accuracyMeters)
        put("timestamp", timestampMs)
    }
}

/**
 * Representasi terpadu untuk semua jenis sinyal (Wi-Fi, Bluetooth LE, & Seluler BTS)
 * sehingga siap diumpankan ke Model Machine Learning On-Device.
 */
data class WirelessSignal(
    val type: SignalType,
    val ssid: String?,           // Wi-Fi SSID, BLE Device Name, atau Operator Seluler (e.g. "Telkomsel")
    val bssid: String,           // Wi-Fi BSSID (MAC), BLE Address, atau Cell Key (MCC-MNC-TAC-CI)
    val rssi: Int,               // Signal level dalam dBm (dBm mentah / RSRP)
    val frequencyMhz: Int?,      // Frekuensi MHz (Wi-Fi / ARFCN / EARFCN)
    val timestampMs: Long,
    val location: GpsCoordinate?,

    // Metadata spesifik Jaringan Seluler (Telephony)
    val connectionStatus: String? = null, // "Registered" (Serving Cell) atau "Unregistered" (Neighboring Cell)
    val radioType: String? = null,        // "LTE", "NR_5G", "GSM", "WCDMA"
    val cellId: Long? = null,             // Cell Identity (CI / CID / NCI)
    val tacOrLac: Int? = null,            // Tracking Area Code (TAC) atau Location Area Code (LAC)
    val mcc: String? = null,              // Mobile Country Code
    val mnc: String? = null,              // Mobile Network Code
    val pci: Int? = null,                 // Physical Cell ID
    val rsrp: Int? = null,                // LTE RSRP (dBm)
    val rsrq: Int? = null,                // LTE RSRQ (dB)
    val cqi: Int? = null,                 // LTE Channel Quality Indicator (CQI)
    val ssRsrp: Int? = null,              // 5G SS-RSRP (dBm)
    val ssRsrq: Int? = null,              // 5G SS-RSRQ (dB)
    val ssSinr: Int? = null               // 5G SS-SINR (dB)
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("ssid", ssid ?: "")
        put("bssid", bssid)
        put("rssi", rssi)
        put("frequency", frequencyMhz ?: -1)
        put("timestamp", timestampMs)
        put("location", location?.toJsonObject() ?: JSONObject.NULL)

        if (type == SignalType.CELLULAR) {
            put("cell_details", JSONObject().apply {
                put("status", connectionStatus ?: "Unregistered")
                put("radio_type", radioType ?: "UNKNOWN")
                put("cell_id", cellId ?: -1L)
                put("tac_or_lac", tacOrLac ?: -1)
                put("mcc", mcc ?: "")
                put("mnc", mnc ?: "")
                put("pci", pci ?: -1)
                put("rsrp", rsrp ?: -999)
                put("rsrq", rsrq ?: -999)
                put("cqi", cqi ?: -1)
                put("ss_rsrp", ssRsrp ?: -999)
                put("ss_rsrq", ssRsrq ?: -999)
                put("ss_sinr", ssSinr ?: -999)
            })
        }
    }
}

/**
 * Agregasi snapshot data untuk feed Machine Learning.
 */
data class WirelessSnapshot(
    val snapshotTimestamp: Long,
    val location: GpsCoordinate?,
    val signals: List<WirelessSignal>
) {
    fun toJson(): String {
        val root = JSONObject().apply {
            put("snapshot_timestamp", snapshotTimestamp)
            put("location", location?.toJsonObject() ?: JSONObject.NULL)
            val signalArray = JSONArray()
            signals.forEach { signalArray.put(it.toJsonObject()) }
            put("signals", signalArray)
        }
        return root.toString()
    }

    /**
     * Mengubah data snapshot menjadi feature vector float 1D ternormalisasi
     * untuk model On-Device Machine Learning (TFLite / ONNX Runtime).
     *
     * @param featureBssids Kamus/dimensi identifier (BSSID / Cell Key) yang dikenal oleh model
     * @param defaultMissingRssi Nilai default untuk sinyal yang tidak terlihat (-100 dBm)
     */
    fun toNormalizedRssiVector(
        featureBssids: List<String>,
        defaultMissingRssi: Float = -100.0f
    ): FloatArray {
        val rssiMap = signals.associate { it.bssid.uppercase() to it.rssi.toFloat() }
        return FloatArray(featureBssids.size) { index ->
            val bssid = featureBssids[index].uppercase()
            val rawRssi = rssiMap[bssid] ?: defaultMissingRssi
            // Min-Max Scaling: (-100..0 dBm -> 0.0..1.0)
            ((rawRssi + 100f) / 100f).coerceIn(0f, 1f)
        }
    }
}
