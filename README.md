# BlazzGrabber

BlazzGrabber adalah aplikasi Android tanpa activity launcher. ADB mengirim URL file ke ponsel, lalu Android `DownloadManager` mengunduhnya langsung. Aplikasi tetap terlihat di Settings dan unduhan ditampilkan oleh notifikasi sistem.

File disimpan di `Download/BlazzMedia/` pada penyimpanan ponsel. Aplikasi menerima URL HTTP atau HTTPS langsung ke file; halaman web yang memerlukan login, cookie, atau header khusus tidak didukung.

## Persyaratan

- JDK 17 atau lebih baru
- Gradle 9.8
- Android SDK Platform 36 dan Build Tools 36.0.0
- Android Platform Tools (`adb`)
- Android 6.0/API 23 atau lebih baru; pengujian dilakukan pada Android 11

Pastikan `local.properties` menunjuk ke root Android SDK yang terpasang di komputer. File ini bersifat lokal dan tidak disimpan di Git.

## Build

Di PowerShell dari root proyek:

```powershell
& 'C:\gradle-9\bin\gradle.bat' --no-daemon :app:assembleDebug
```

APK hasil build:

```text
app/build/outputs/named-apk/blazz-grabber.apk
```

## Instalasi

Hubungkan ponsel dengan USB debugging atau Wireless debugging, lalu pastikan ADB menampilkan perangkat:

```powershell
adb devices -l
adb install -r .\app\build\outputs\named-apk\blazz-grabber.apk
```

Pada perangkat Samsung yang menahan aplikasi tanpa launcher di standby, aktifkan bucket aplikasi setelah instalasi:

```powershell
adb shell am set-standby-bucket com.blazzgrabber active
```

## Mengunduh File

Kirim URL file langsung melalui broadcast ADB. Opsi `--include-stopped-packages` diperlukan untuk aplikasi tanpa activity launcher, terutama setelah instalasi baru.

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.UNDUH_VIDEO `
  -p com.blazzgrabber `
  --es url "https://example.com/video.mp4"
```

Unduhan diproses oleh Android setelah broadcast selesai. ADB hanya membawa URL, bukan isi video.

## Memantau Unduhan

Lihat pesan aplikasi:

```powershell
adb logcat -s BlazzResponse
```

Marker yang dicatat:

- `UNDUH_DIMULAI id=...` - DownloadManager menerima permintaan
- `UNDUH_STATUS id=... status=... bytes=... total=... reason=...` - status dan progres terkini
- `UNDUH_SELESAI id=...` - file selesai diunduh
- `UNDUH_GAGAL id=... alasan=...` - URL, permintaan, atau unduhan gagal

Untuk meminta status satu unduhan, gunakan ID dari `UNDUH_DIMULAI`:

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.CEK_STATUS `
  -p com.blazzgrabber `
  --el download_id 1234
```

File yang selesai tersedia di:

```text
/sdcard/Download/BlazzMedia/
```