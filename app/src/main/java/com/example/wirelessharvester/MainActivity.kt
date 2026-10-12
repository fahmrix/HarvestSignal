package com.example.wirelessharvester

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.wirelessharvester.data.GpsCoordinate
import com.example.wirelessharvester.data.WirelessSignal
import com.example.wirelessharvester.utils.HarvesterPermissions
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), WirelessHarvester.HarvesterListener {

    private lateinit var harvester: WirelessHarvester

    private lateinit var tvStatus: TextView
    private lateinit var tvGpsInfo: TextView
    private lateinit var tvSignalCounter: TextView
    private lateinit var tvConsoleLog: TextView
    private lateinit var scrollConsole: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExportJson: Button
    private lateinit var btnProcessMl: Button
    private lateinit var btnShareFile: Button
    private lateinit var btnShareCsv: Button
    private lateinit var radarView: RadarView

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // Activity Result Launcher untuk penanganan izin runtime secara dinamis
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.entries.all { it.value }
        if (allGranted) {
            appendLog("[Izin Diberikan] Semua izin lokasi, seluler, dan nirkabel disetujui.")
            startHarvestingProcess()
        } else {
            val rejected = permissionsMap.filter { !it.value }.keys.joinToString(", ")
            appendLog("[Izin Ditolak] Perangkat menolak: $rejected")
            Toast.makeText(this, "Izin lokasi & nirkabel wajib diberikan!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        harvester = WirelessHarvester(this)
        harvester.listener = this

        btnStart.setOnClickListener {
            if (HarvesterPermissions.hasAllPermissions(this)) {
                startHarvestingProcess()
            } else {
                appendLog("[Izin Diperlukan] Meminta izin runtime...")
                requestPermissionsLauncher.launch(HarvesterPermissions.getRequiredPermissions())
            }
        }

        btnStop.setOnClickListener {
            stopHarvestingProcess()
        }

        btnExportJson.setOnClickListener {
            exportDataToJson()
        }

        btnProcessMl.setOnClickListener {
            feedDataToOnDeviceMl()
        }

        btnShareFile.setOnClickListener {
            saveAndShareFile()
        }

        btnShareCsv.setOnClickListener {
            saveAndShareCsv()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvGpsInfo = findViewById(R.id.tvGpsInfo)
        tvSignalCounter = findViewById(R.id.tvSignalCounter)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        scrollConsole = findViewById(R.id.scrollConsole)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExportJson = findViewById(R.id.btnExportJson)
        btnProcessMl = findViewById(R.id.btnProcessMl)
        btnShareFile = findViewById(R.id.btnShareFile)
        btnShareCsv = findViewById(R.id.btnShareCsv)
        radarView = findViewById(R.id.radarView)
    }

    private fun saveAndShareFile() {
        val snapshot = harvester.captureCurrentSnapshot()
        if (snapshot.signals.isEmpty()) {
            appendLog("[Share Warning] Belum ada data sinyal untuk disimpan. Lakukan scan terlebih dahulu.")
            Toast.makeText(this, "Belum ada data untuk dibagikan!", Toast.LENGTH_SHORT).show()
            return
        }

        val jsonString = snapshot.toJson()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "wireless_harvest_$timeStamp.json"

        try {
            val exportDir = File(cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val jsonFile = File(exportDir, fileName)
            jsonFile.writeText(jsonString)

            val fileUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                jsonFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Hasil Panen Sinyal Wireless ($fileName)")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "File dataset nirkabel Wi-Fi & BLE (${snapshot.signals.size} sinyal terdeteksi).\nWaktu: $timeStamp"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            appendLog("[File Tersimpan] ${jsonFile.absolutePath} (${jsonFile.length()} bytes)")
            startActivity(Intent.createChooser(shareIntent, "Bagikan Dataset JSON via..."))
        } catch (e: Exception) {
            appendLog("[Error Share] Gagal menyimpan/membagikan file: ${e.message}")
            Toast.makeText(this, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndShareCsv() {
        val snapshot = harvester.captureCurrentSnapshot()
        if (snapshot.signals.isEmpty()) {
            appendLog("[CSV Warning] Belum ada data sinyal untuk disimpan. Lakukan scan terlebih dahulu.")
            Toast.makeText(this, "Belum ada data untuk dibagikan!", Toast.LENGTH_SHORT).show()
            return
        }

        val csvString = snapshot.toCsv(includeHeader = true)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "wireless_harvest_$timeStamp.csv"

        try {
            val exportDir = File(cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val csvFile = File(exportDir, fileName)
            csvFile.writeText(csvString)

            val fileUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Dataset Sinyal Nirkabel CSV ($fileName)")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Dataset nirkabel hybrid CSV (${snapshot.signals.size} sinyal terdeteksi).\nWaktu: $timeStamp\nKolom: Timestamp,Latitude,Longitude,GPS_Accuracy,Signal_Type,Device_ID,Device_Name,Signal_Strength"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            appendLog("[File CSV Tersimpan] ${csvFile.absolutePath} (${csvFile.length()} bytes)")
            startActivity(Intent.createChooser(shareIntent, "Bagikan Dataset CSV via..."))
        } catch (e: Exception) {
            appendLog("[Error Share CSV] Gagal menyimpan/membagikan: ${e.message}")
            Toast.makeText(this, "Gagal membagikan CSV: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startHarvestingProcess() {
        try {
            harvester.startHarvesting()
            radarView.setScanning(true)
            tvStatus.text = "Status: Memanen Data Sinyal Aktif..."
            tvStatus.setTextColor(0xFF00E676.toInt()) // Hijau
            appendLog("[Started] Pemanenan nirkabel (Wi-Fi & BLE) dimulai.")
        } catch (e: SecurityException) {
            appendLog("[Error Security] ${e.message}")
            Toast.makeText(this, "Gagal memulai: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopHarvestingProcess() {
        harvester.stopHarvesting()
        radarView.setScanning(false)
        tvStatus.text = "Status: Siaga (Dihentikan)"
        tvStatus.setTextColor(0xFF38BDF8.toInt()) // Biru
        appendLog("[Stopped] Pemanenan data dihentikan.")
    }

    private fun exportDataToJson() {
        val snapshot = harvester.captureCurrentSnapshot()
        val jsonString = snapshot.toJson()
        appendLog("[JSON Snapshot (${snapshot.signals.size} sinyal)]:\n$jsonString")
        Toast.makeText(this, "Snapshot diekspor! Cek log console.", Toast.LENGTH_SHORT).show()
    }

    private fun feedDataToOnDeviceMl() {
        val snapshot = harvester.captureCurrentSnapshot()
        if (snapshot.signals.isEmpty()) {
            appendLog("[ML Warning] Belum ada sinyal yang tertangkap. Jalankan scan terlebih dahulu.")
            return
        }

        // Contoh daftar BSSID target sebagai dimensi fitur model (Input Feature Vector)
        val sampleModelFeatures = listOf(
            "AA:BB:CC:11:22:33",
            "DD:EE:FF:44:55:66",
            "00:11:22:33:44:55",
            "AA:11:BB:22:CC:33"
        )

        // Konversi ke FloatArray ternormalisasi [0.0 s/d 1.0] siap umpan ke TFLite / ONNX Runtime
        val normalizedTensor = snapshot.toNormalizedRssiVector(sampleModelFeatures)
        val vectorStr = normalizedTensor.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }

        appendLog("[ML Vector Ready] Dimensi: ${normalizedTensor.size} | Nilai: $vectorStr")
        Toast.makeText(this, "Tensor FloatArray siap untuk model ML!", Toast.LENGTH_SHORT).show()
    }

    override fun onSignalCaptured(signal: WirelessSignal, totalWifi: Int, totalBle: Int, totalCellular: Int) {
        runOnUiThread {
            radarView.submitSignal(signal)
            tvSignalCounter.text = "Sinyal: Wi-Fi: $totalWifi | BLE: $totalBle | BTS: $totalCellular | Total: ${totalWifi + totalBle + totalCellular}"
            val time = timeFormat.format(Date(signal.timestampMs))
            val locStr = signal.location?.let { String.format(Locale.US, " (Lat: %.5f, Lng: %.5f)", it.latitude, it.longitude) } ?: ""

            val info = if (signal.type == com.example.wirelessharvester.data.SignalType.CELLULAR) {
                val cellDetail = "[${signal.radioType}] [${signal.connectionStatus}] CI:${signal.cellId ?: "N/A"} TAC:${signal.tacOrLac ?: "N/A"} | RSRP:${signal.rsrp ?: signal.ssRsrp ?: signal.rssi}dBm"
                "[$time] [BTS] ${signal.ssid} | $cellDetail$locStr"
            } else {
                "[$time] [${signal.type}] ${signal.ssid ?: "<hidden>"} | ${signal.bssid} | ${signal.rssi} dBm$locStr"
            }
            appendLog(info)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onLocationUpdated(gps: GpsCoordinate) {
        tvGpsInfo.text = String.format(
            Locale.US,
            "GPS: Lat: %.5f, Lng: %.5f (Akurasi: ±%.1fm)",
            gps.latitude,
            gps.longitude,
            gps.accuracyMeters
        )
    }

    private fun appendLog(message: String) {
        tvConsoleLog.append("\n$message")
        scrollConsole.post { scrollConsole.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        radarView.setScanning(false)
        harvester.stopHarvesting()
        super.onDestroy()
    }
}
