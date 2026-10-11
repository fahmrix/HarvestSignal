# Wireless Harvester (Android Native)

Aplikasi Android native (Kotlin) & utility class untuk continuous wireless data harvesting (Wi-Fi Scan & Bluetooth Low Energy Scan) yang disinkronisasikan secara real-time dengan koordinat GPS (Latitude, Longitude, Akurasi), siap diumpankan ke model Machine Learning on-device (TFLite / ONNX Runtime).

---

## Fitur Utama

1. **Continuous Wi-Fi & BLE Scanning**:
   - Loop pemindaian Wi-Fi agresif (~2 detik interval).
   - BLE Scanning dalam mode `SCAN_MODE_LOW_LATENCY` dan `setReportDelay(0)` (continuous duty cycle 100% tanpa sleep cycle).
2. **Wi-Fi Scan Throttling Bypass**:
   - Kompatibel dan optimal jika pembatasan pemindaian dinonaktifkan di Developer Options.
3. **Data Harvesting Mentah**:
   - SSID, BSSID (MAC Address), RSSI (dBm), Frekuensi MHz, Timestamp, dan data GPS real-time.
4. **On-Device Machine Learning Ready**:
   - Serialisasi ke format JSON snapshot.
   - Fungsi konversi otomatis ke 1D FloatArray Feature Vector (`toNormalizedRssiVector`) dengan min-max scaling (-100 dBm s/d 0 dBm $\rightarrow$ 0.0 s/d 1.0).
5. **Runtime Permissions Handled**:
   - Mendukung kompatibilitas dari Android 8.0 (API 26) hingga Android 14+ (API 34), termasuk penanganan dinamis izin `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, dan `ACCESS_FINE_LOCATION`.
6. **Automated GitHub Actions CI/CD**:
   - Terintegrasi workflow `.github/workflows/build-apk.yml` yang langsung meng-compile project menjadi APK debug dan mengunggahnya ke Artifacts.

---

## Struktur Folder Project

```
r/
├── .github/
│   └── workflows/
│       └── build-apk.yml              # CI/CD Workflow build APK
├── app/
│   ├── build.gradle.kts               # Dependencies & SDK config
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml    # Deklarasi izin & hardware
│           ├── java/com/example/wirelessharvester/
│           │   ├── MainActivity.kt    # UI controller & Permission launcher
│           │   ├── WirelessHarvester.kt # Core Utility Class pemanen sinyal
│           │   ├── data/
│           │   │   └── HarvestModel.kt  # Model data, JSON & ML Normalizer
│           │   └── utils/
│           │       └── HarvesterPermissions.kt # Helper izin runtime
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
2. Buka tab **Actions** di repositori GitHub Anda.
3. Workflow **"Build Android APK"** akan berjalan otomatis.
4. Setelah selesai (centang hijau), klik run tersebut dan unduh APK dari bagian **Artifacts** (`wireless-harvester-debug-apk`).

### 2. Mematikan Wi-Fi Scan Throttling di HP Android
Untuk mendapatkan laju pemindaian Wi-Fi maksimal tanpa throttling dari sistem Android (Android 9 ke atas):
1. Buka **Settings** -> **About Phone** -> Ketuk **Build Number** 7 kali hingga opsi pengembang aktif.
2. Buka **Developer Options (Opsi Pengembang)**.
3. Gulir ke bagian **Networking**.
4. Matikan toggle **"Wi-Fi scan throttling"** (Pembatasan pemindaian Wi-Fi).
