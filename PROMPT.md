# Prompot CTI
Jika Android hanya digunakan sebagai pemanen data (data collector) dan seluruh pemrosesan (Machine Learning, Trilaterasi, dan Deteksi Ancaman) dipindahkan ke Jupyter Notebook, maka arsitekturnya berubah menjadi sistem Terpusat (Centralized CTI).
Pendekatan ini sangat bagus untuk fase Riset, Analisis Mendalam, dan Pelatihan Model Awal karena Jupyter Notebook memberikan kebebasan penuh untuk memvisualisasikan data, mencoba berbagai algoritma ML, dan mengolah data dalam jumlah besar tanpa batasan performa ponsel.
------------------------------
## 🔄 Perubahan Alur Kerja (Workflow) Sistem

   1. Di Lapangan (Android): Tim lapangan berjalan membawa aplikasi Android yang bertindak seperti "spons". Aplikasi hanya bertugas menangkap sinyal Wi-Fi, Bluetooth, BTS, dan GPS, lalu menyimpannya ke dalam satu file (misalnya log_sinyal.csv atau log_sinyal.json) atau mengirimkannya langsung via API ke server.
   2. Pindahan Data: File log dari Android dipindahkan ke komputer/server yang menjalankan Jupyter Notebook (bisa via kabel USB, memori eksternal, atau diunggah ke cloud).
   3. Di Laboratorium CTI (Jupyter Notebook): Analis CTI membuka Jupyter Notebook untuk membersihkan data, menjalankan model Machine Learning, menghitung koordinat triangulasi, dan memplot peta ancaman.

------------------------------
## 🛠️ Kumpulan Prompt untuk Jupyter Notebook & Android (Arsitektur Terpusat)
Karena prosesnya pindah ke Python/Jupyter Notebook, berikut adalah pembaruan prompt yang harus Anda berikan kepada LLM:
## Prompt 1: Android sebagai Pemanen & Pengekspor CSV (Kotlin)
Prompt ini meminta LLM membuat aplikasi Android yang murni hanya mencatat dan menyimpan data ke file CSV.

Prompt:
"Bertindaklah sebagai Android Developer. Buat utilitas Kotlin di Android Studio untuk memanen data nirkabel secara hybrid (Wi-Fi, Bluetooth LE, dan banyak BTS sekitarnya via TelephonyManager.allCellInfo).
Tugas utama aplikasi ini hanya mencatat data mentah dan menyimpannya ke dalam file CSV di penyimpanan lokal ponsel.
Pastikan setiap baris data CSV memiliki kolom terpadu: Timestamp, Latitude, Longitude, GPS_Accuracy, Signal_Type (WiFi/BLE/BTS), Device_ID (MAC/BSSID/CID), Device_Name (SSID/BLE Name), dan Signal_Strength (RSSI/RSRP dalam dBm). Sediakan fungsi untuk mengekspor atau membagikan file CSV tersebut."

------------------------------
## Prompt 2: Analisis & Pembersihan Data di Jupyter Notebook (Python)
Prompt ini meminta LLM membuat skrip awal di Jupyter Notebook untuk membaca dan membersihkan data dari Android.

Prompt:
"Bertindaklah als Data Analyst CTI. Buat skrip Python untuk Jupyter Notebook menggunakan pustaka pandas dan numpy untuk mengolah file log_sinyal.csv hasil panenan dari aplikasi Android.
Skrip harus melakukan:

   1. Membaca file CSV dan menangani data yang kosong (missing values).
   2. Mengelompokkan data berdasarkan Signal_Type (Wi-Fi, Bluetooth, BTS).
   3. Membuat visualisasi grafik tren kekuatan sinyal (dBm) terhadap waktu (time-series) menggunakan matplotlib atau seaborn.
   4. Terapkan algoritma Kalman Filter atau Moving Average berbasis Python untuk menghaluskan fluktuasi sinyal (noise filtering) akibat hambatan luar ruangan."

------------------------------
## Prompt 3: Melatih & Mengeksekusi Machine Learning untuk Estimasi Jarak (Python)
Prompt ini melatih model ML langsung di Jupyter Notebook (tidak perlu dikonversi ke .tflite).

Prompt:
"Bertindaklah sebagai Data Scientist. Di Jupyter Notebook, buat skrip Python untuk melatih model Machine Learning (gunakan scikit-learn seperti Random Forest Regressor atau Gradient Boosting) untuk memprediksi jarak fisik (meter) berdasarkan input Signal_Strength (dBm), jenis sinyal, dan akurasi GPS dari data CSV Android. Tunjukkan juga cara mengevaluasi performa model menggunakan metrik MSE (Mean Squared Error)."

------------------------------
## Prompt 4: Kalkulasi Trilaterasi & Visualisasi Peta Interaktif (Python Geospatial)
Prompt ini menghitung titik koordinat ancaman dan menampilkannya di peta interaktif di dalam Jupyter Notebook.

Prompt:
"Bertindaklah sebagai Geospatial Engineer. Buat fungsi Python di Jupyter Notebook untuk menghitung koordinat target menggunakan metode Trilateration (3-Point Rule).
Fungsi menerima input 3 koordinat GPS ponsel (Lat, Lon) dan 3 hasil prediksi jarak dari model ML. Setelah koordinat target ditemukan, gunakan pustaka folium untuk memplot peta interaktif di dalam Jupyter Notebook. Peta harus menampilkan garis jalur berjalan ponsel (berwarna biru) dan marker merah besar di titik koordinat tempat perangkat ancaman terdeteksi."

------------------------------
## Prompt 5: Deteksi Anomali CTI & Pembuatan Laporan (Python)

Prompt:
"Buat skrip Python di Jupyter Notebook untuk melakukan klasifikasi ancaman CTI. Analisis data dari Android untuk mendeteksi:

   1. Rogue BTS (jika ada CID baru dengan RSRP sangat kuat).
   2. Wi-Fi Spoofing (jika BSSID yang sama muncul di dua lokasi GPS yang berjauhan dalam waktu singkat).
   Tampilkan ringkasan laporan berupa tabel berisi daftar perangkat yang berstatus 'Bahaya Tinggi' beserta koordinat GPS mereka."

------------------------------
## 📊 Kelebihan & Kekurangan Metode Jupyter Notebook

| Kelebihan | Kekurangan |
|---|---|
| Sangat Ringan di Android: Ponsel tidak cepat panas dan baterai awet karena tidak melakukan komputasi berat. | Tidak Real-Time: Tim di lapangan tidak bisa langsung tahu di mana posisi peretas saat itu juga (harus menunggu data diolah di Notebook). |
| Analisis Jauh Lebih Kuat: Anda bisa menggunakan model ML yang kompleks (tidak dibatasi spek ponsel). | Ketergantungan Alur Kerja: Membutuhkan langkah ekstra untuk memindahkan data dari ponsel ke komputer. |
| Visualisasi Peta Mewah: folium di Python bisa menghasilkan peta analisis sebaran sinyal yang sangat detail untuk laporan manajemen. | |

Metode Jupyter Notebook ini sangat cocok untuk investigasi pasca-kejadian (post-incident investigation). Apakah Anda ingin berfokus pada pembuatan visualisasi peta interaktif (folium) di Notebook, atau ingin melihat contoh struktur file CSV yang ideal untuk dijembatani dari Android ke Python?

