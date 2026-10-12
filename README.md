# Wireless Harvester (Hybrid Trilateration & Threat Hunting Tool)

Aplikasi native Android (Kotlin) untuk **Trilaterasi Multi-Sinyal (Hybrid Trilateration)** yang menggabungkan tiga jenis gelombang radio nirkabel sekaligus: **BTS Seluler (Telephony)**, **Bluetooth Low Energy (BLE)**, dan **Wi-Fi**.

Dalam dunia keamanan siber, aplikasi taktis seperti ini sering disebut sebagai **Wireless Threat Hunting & Localization Tool (Alat Pemburu dan Pelokalisasi Ancaman Nirkabel)**. Sistem ini memanen data emisi radio secara kontinu (*continuous harvesting*) yang disinkronisasikan secara presisi dengan koordinat GPS ponsel (Latitude, Longitude, Akurasi), siap diumpankan ke model Machine Learning lokal (*On-Device ML* via TFLite / ONNX Runtime) untuk pelokalisasian posisi anomali, fingerprinting radio, atau audit spektrum.

---

## Fitur Utama

1. **Radar Deteksi Sinyal Animasi**:
   - Sapuan radar 360 derajat berjalan secara real-time selama pemindaian aktif.
   - Blip dibedakan berdasarkan sumber: Wi-Fi (cyan), BLE (ungu), dan BTS (kuning).
   - Posisi radial memvisualisasikan kekuatan sinyal; sinyal yang lebih kuat tampil lebih dekat ke pusat.
   - Sinyal lama memudar otomatis agar tampilan tetap fokus pada perangkat yang masih aktif.
2. **Trilaterasi Multi-Sinyal (Hybrid Multi-Source RF)**:
   - **BTS Seluler (Telephony)**: Memanen data multi-menara sekaligus (*Serving Cell* dan *Neighboring Cells*) untuk jaringan 4G LTE, 5G NR (New Radio), 3G WCDMA, dan 2G GSM.
   - **Wi-Fi Scanning**: Loop pemindaian frekuensi Wi-Fi agresif (2.4 GHz, 5 GHz, 6 GHz).
   - **Bluetooth Low Energy (BLE)**: Mode `SCAN_MODE_LOW_LATENCY` dan `setReportDelay(0)` (continuous duty cycle 100% tanpa sleep cycle) untuk menangkap paket advertisement beacon instan.
3. **Metrik Kualitas Sinyal Mendalam**:
   - **Seluler (LTE/5G)**: Cell ID (CI/NCI), TAC/LAC, MCC, MNC, PCI, RSRP (dBm), RSRQ (dB), CQI, SS-RSRP, SS-RSRQ, dan SS-SINR.
   - **Wi-Fi & BLE**: SSID, BSSID (MAC Address), RSSI (dBm), frekuensi kanal (MHz).
   - **Status Koneksi**: Penanda otomatis BTS *Registered* (terhubung aktif) vs *Unregistered* (tetangga sekitar).
4. **Wi-Fi Scan Throttling Bypass**:
   - Kompatibel dan optimal jika pembatasan pemindaian dinonaktifkan di Developer Options ponsel.
5. **On-Device Machine Learning Pipeline Ready**:
   - Struktur data JSON seragam di mana semua sinyal berbagi timestamp dan koordinat GPS yang sama per snapshot.
   - Fungsi konversi otomatis ke 1D FloatArray Feature Vector (`toNormalizedRssiVector`) dengan min-max scaling (-100 dBm s/d 0 dBm $\rightarrow$ 0.0 s/d 1.0).
6. **Pencatatan Data Mentah & Ekspor Terpadu (CSV & JSON)**:
   - **Format Kolom CSV Terpadu**:
     `Timestamp,Latitude,Longitude,GPS_Accuracy,Signal_Type,Device_ID,Device_Name,Signal_Strength`
     - `Signal_Type`: `WiFi`, `BLE`, atau `BTS`
     - `Device_ID`: MAC Address (Wi-Fi), BLE Address, atau Cell Identity (CID/CI/NCI)
     - `Device_Name`: SSID Wi-Fi, Nama Beacon BLE, atau Nama Operator Seluler
     - `Signal_Strength`: RSSI / RSRP dalam dBm
   - Simpan dataset hasil panen langsung ke file `.csv` atau `.json` di penyimpanan lokal ponsel dan bagikan via Share Sheet bawaan ponsel (WhatsApp, Google Drive, Email, Bluetooth, dll).
7. **Penanganan Izin Runtime Lengkap**:
   - Kompatibel dari Android 8.0 (API 26) hingga Android 14+ (API 34): `READ_PHONE_STATE`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, dan `ACCESS_WIFI_STATE`.
8. **Automated GitHub Actions CI/CD**:
   - Terintegrasi workflow `.github/workflows/build-apk.yml` yang otomatis mengompilasi proyek menjadi APK siap pakai di tab GitHub Artifacts.

---

## Struktur Folder Project

```
r/
├── .github/
│   └── workflows/
│       └── build-apk.yml              # CI/CD Workflow build APK
├── app/
│   ├── build.gradle.kts               # Dependencies & SDK 34 config
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml    # Deklarasi izin & hardware
│           ├── java/com/example/wirelessharvester/
│           │   ├── MainActivity.kt    # UI controller, File Share & Permission launcher
│           │   ├── WirelessHarvester.kt # Core Utility Class pemanen sinyal (Cellular, Wi-Fi, BLE)
│           │   ├── data/
│           │   │   └── HarvestModel.kt  # Model data, JSON & ML Normalizer
│           │   └── utils/
│           │       └── HarvesterPermissions.kt # Helper izin runtime multi-versi Android
│           └── res/                   # Layout & UI Resources
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.properties
│       └── gradle-wrapper.jar
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```

---

## Petunjuk Penggunaan & Kompilasi APK

### 1. Build Otomatis via GitHub Actions
1. Push project ini ke repositori GitHub Anda:
   ```bash
   git init
   git add .
   git commit -m "feat: initial wireless harvester project"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO_NAME.git
   git push -u origin main
   ```
2. Buka tab **Actions** di repositori GitHub Anda: `https://github.com/fahmrix/HarvestSignal/actions`.
3. Workflow **"Build Android APK"** akan berjalan otomatis.
4. Setelah selesai (centang hijau), klik run tersebut dan unduh APK dari bagian **Artifacts** (`wireless-harvester-debug-apk`).

### 2. Mematikan Wi-Fi Scan Throttling di HP Android
Untuk mendapatkan laju pemindaian Wi-Fi maksimal tanpa pembatasan bawaan sistem Android (Android 9 ke atas):
1. Buka **Settings** -> **About Phone** -> Ketuk **Build Number** 7 kali hingga opsi pengembang aktif.
2. Buka **Developer Options (Opsi Pengembang)**.
3. Gulir ke bagian **Networking**.
4. Matikan toggle **"Wi-Fi scan throttling"** (Pembatasan pemindaian Wi-Fi).
