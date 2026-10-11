package com.example.wirelessharvester.data

import org.json.JSONArray
import org.json.JSONObject

enum class SignalType { WIFI, BLE }

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

data class WirelessSignal(
    val type: SignalType,
    val ssid: String?,           // Nama jaringan (SSID Wi-Fi atau Nama Perangkat BLE)
    val bssid: String,           // MAC Address / Device Address
    val rssi: Int,               // Sinyal mentah dalam dBm (-100 s/d 0)
    val frequencyMhz: Int?,      // Frekuensi khusus Wi-Fi (misal: 2412, 5180), null untuk BLE
    val timestampMs: Long,
    val location: GpsCoordinate?
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("ssid", ssid ?: "")
        put("bssid", bssid)
        put("rssi", rssi)
        put("frequency", frequencyMhz ?: -1)
        put("timestamp", timestampMs)
        put("location", location?.toJsonObject() ?: JSONObject.NULL)
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
     * @param featureBssids Kamus/dimensi BSSID yang dikenal oleh model
     * @param defaultMissingRssi Nilai default untuk BSSID yang tidak terlihat (-100 dBm)
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
