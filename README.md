# BlazzGrabber

BlazzGrabber adalah aplikasi Android tanpa activity launcher. ADB mengirim URL file ke ponsel, lalu Android `DownloadManager` mengunduhnya langsung. Aplikasi tetap terlihat di Settings dan unduhan ditampilkan oleh notifikasi sistem.

File disimpan di `Download/BlazzMedia/` pada penyimpanan ponsel. Aplikasi menerima URL HTTPS langsung ke file; URL `http://` ditolak secara default (`allow_http=false`) dengan `BAD_URL`. Halaman web yang memerlukan login, cookie, atau header khusus tidak didukung.

## Persyaratan

- JDK 17 atau lebih baru
- Gradle 9.8
- Android SDK Platform 36 dan Build Tools 36.0.0
- Android Platform Tools (`adb`)
- Android 6.0/API 23 atau lebih baru; pengujian dilakukan pada Android 7, 9, dan 11

Pastikan `local.properties` menunjuk ke root Android SDK yang terpasang di komputer. File ini bersifat lokal dan tidak disimpan di Git.

## Konfigurasi Build

Ubah nilai build-time di [`app-config.properties`](app-config.properties). Gradle membacanya saat build dan membenamkan nilai serta asset terpilih ke APK; aplikasi tidak membaca file config ini saat berjalan.

| Key | Fungsi |
|---|---|
| `app_name` | Nama aplikasi di Settings dan judul notifikasi |
| `app_icon` | Path ikon PNG relatif ke folder `app` |
| `notification_sound` | Path MP3 saat unduhan/progres relatif ke folder `app` |
| `notification_sound_success` | Path MP3 untuk `type=complete` atau `type=success` |
| `notification_sound_error` | Path MP3 untuk `type=error` |
| `download_folder` | Subfolder di `Download/` pada ponsel |
| `apk_file_name` | Nama APK hasil build |
| `notification_silent_channel_id` | ID channel notifikasi tanpa suara |
| `notification_sound_channel_id` | ID channel notifikasi bersuara |

Ikon harus PNG dan sound effect harus MP3 karena nama resource Android ditetapkan oleh build. Jika mengganti suara tetapi ingin Android menerapkan suara channel baru, ganti juga `notification_sound_channel_id`; preferensi channel lama disimpan oleh Android.

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

Pada Android 13/API 33 atau lebih baru, berikan izin notifikasi melalui ADB:

```powershell
adb shell pm grant com.blazzgrabber android.permission.POST_NOTIFICATIONS
```

## Izin Setelah Instalasi

Selain izin lama, rilis ini memakai izin tambahan untuk mengelola file di `Download/BlazzMedia/` dan memindahkannya ke urutan teratas galeri.

Android 11+ (scoped storage) — wajib agar `TOUCH` dapat membaca dan menyalin file:

```powershell
adb shell appops set com.blazzgrabber MANAGE_EXTERNAL_STORAGE allow
```

Android ≤ 10 — wajib `WRITE`/`READ_EXTERNAL_STORAGE` seperti sebelumnya:

```powershell
adb shell pm grant com.blazzgrabber android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.blazzgrabber android.permission.READ_EXTERNAL_STORAGE
```

Android 13+ — izin media (dipakai `CEK_FILE`/`TOUCH` untuk membaca baris MediaStore gambar dan video):

```powershell
adb shell pm grant com.blazzgrabber android.permission.READ_MEDIA_IMAGES
adb shell pm grant com.blazzgrabber android.permission.READ_MEDIA_VIDEO
```

Android 13+ — izin notifikasi:

```powershell
adb shell pm grant com.blazzgrabber android.permission.POST_NOTIFICATIONS
```

Cek status izin kapan saja lewat action `INFO` (lihat bagian INFO).

Semua receiver dibatasi dengan `android:permission="android.permission.DUMP"`, sehingga hanya shell/ADB yang dapat mengirim perintah. Aplikasi lain di ponsel tidak bisa memicunya.

## Respons JSON

Semua action baru membalas **JSON satu baris** lewat `setResultData(...)`, terbaca pada keluaran `am broadcast -W` sebagai `data="{...}"`. Field `ok` adalah penentu utama, dan `ref` dipantulkan bila dikirim.

## Konfigurasi Keamanan Unduhan

`app-config.properties` menyediakan dua nilai build-time:

| Key | Fungsi |
|---|---|
| `allow_http` | `false` (default) menolak URL `http://` dengan `BAD_URL` |
| `allowed_hosts` | Daftar host yang diizinkan, dipisah koma. Kosong berarti semua host |

Build default mengosongkan `allowed_hosts`, sehingga **semua host diizinkan** (pemakaian universal). Isi daftar (mis. `get.blazz.biz.id`) bila ingin membatasi pada build produksi. Host di luar daftar ditolak dengan `HOST_NOT_ALLOWED`.

Catatan: hanya host pada URL awal yang diperiksa. Host **setelah redirect** belum ikut divalidasi.

## Mengunduh File

Kirim URL file langsung melalui broadcast ADB. Opsi `--include-stopped-packages` diperlukan untuk aplikasi tanpa activity launcher, terutama setelah instalasi baru.

### Extras opsional baru

| Extra | Tipe | Keterangan |
|---|---|---|
| `filename` | string | Nama file tujuan; bila kosong diambil dari URL |
| `ref` | string | Nilai buram yang dipantulkan pada respons |
| `expected_size` | long | Ukuran byte yang diharapkan |
| `if_exists` | string | `skip` atau `replace` |

Respons: `{"ok":true,"state":"STARTED","download_id":1234,"filename":"...","ref":"..."}` atau `state:"EXISTS"` untuk `if_exists=skip`. Respons `EXISTS` menyertakan `size` dan `gallery_rank`, sehingga worker tidak perlu memanggil `CEK_FILE` lagi.

Kode galat: `BAD_URL`, `HOST_NOT_ALLOWED`, `BAD_ARGS`, `NO_SPACE`, `DOWNLOAD_FAILED`, `INTERNAL`. Daftar lengkap tiap action ada di [Kode Galat per Action](#kode-galat-per-action).

### Contoh dengan `filename` dan `ref`

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.UNDUH_VIDEO `
  -p com.blazzgrabber `
  --es url "https://get.blazz.biz.id/video.mp4" `
  --es filename "1u-pje5j.mp4" `
  --es ref "item-9812" `
  --el expected_size 21107071 `
  --es if_exists skip
```

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.UNDUH_VIDEO `
  -p com.blazzgrabber `
  --es url "https://example.com/video.mp4"
```

Unduhan diproses oleh Android setelah broadcast selesai. ADB hanya membawa URL, bukan isi file.

`UNDUH_VIDEO` menerima file apa pun lewat URL. Ekstensi gambar (`.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`, `.heic`, `.heif`, `.bmp`) diperlakukan sebagai **gambar** dan diindeks ke koleksi MediaStore Images; sisanya dianggap video. `CEK_FILE`, `TOUCH`, `CEK_STATUS` (`indexed`), dan `CLEANUP` otomatis memakai koleksi yang sesuai jenis file.

## Notifikasi dari ADB

Action notifikasi berdiri sendiri dan tidak memulai atau mengubah unduhan. Extra `title` bersifat opsional; jika tidak dikirim atau kosong, judul memakai nama aplikasi dari config. Sound dipilih dari tiga file di `app-config.properties`: `progress`/`download` memakai sound unduhan, `complete`/`success` memakai sound sukses, dan `error` memakai sound error. Tambahkan `--es sound none` untuk membuat notifikasi senyap.

| Extra | Tipe | Keterangan |
|---|---|---|
| `type` | string | Wajib. `progress`, `download`, `complete`, `success`, atau `error` |
| `text` | string | Wajib, maksimal 200 karakter |
| `title` | string | Opsional; default nama aplikasi |
| `sound` | string | `blazz` atau `none` |
| `id` | string | Opsional; memperbarui item notifikasi yang sama |
| `image` | string | Opsional; URL gambar `http`/`https` untuk thumbnail |

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es type progress `
  --es text "Proses unduh dimulai"

adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es title "Video x-123.mp4 selesai" `
  --es type complete `
  --es text "Unduhan video selesai tersimpan di gallery"
```

Extra `--es sound blazz` mengaktifkan sound sesuai jenis notifikasi, sedangkan `--es sound none` mematikannya. Suara Android dikendalikan melalui channel terpisah untuk download, sukses, dan error; pengguna tetap dapat mengubah atau membisukan tiap channel di Settings. Teks notifikasi dibatasi hingga 200 karakter. Android menampilkan waktu relatif notifikasi secara otomatis, misalnya `1j`.

Tanpa extra `id`, setiap broadcast membuat item notifikasi baru sehingga notifikasi sebelumnya tetap ada. Untuk memperbarui item yang sama, kirim `id` yang sama pada setiap tahap. Contoh ini membuat satu item untuk sebuah proses upload:

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es id "upload-42" `
  --es type progress `
  --es text "Sedang mengunggah video..."

adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es id "upload-42" `
  --es type complete `
  --es text "Upload selesai"
```

Notifikasi menggunakan tampilan teks panjang Android. Tampilan ringkas tetap dibatasi ruang layar; bentangkan notifikasi untuk membaca seluruh teks beberapa baris.

Contoh untuk proses lain:

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es type progress `
  --es text "Sedang mengunggah video..."
```
Dengan suara
```
adb -s adb-RR8M60FA87W-iJeRp2._adb-tls-connect._tcp shell "am broadcast -W --include-stopped-packages -a com.blazzgrabber.NOTIFY -p com.blazzgrabber --es type complete --es id 'text-quote-test-2' --es text 'Tes suara Blazz Apps' --es sound blazz"
```
Tanpa suara
```
adb -s adb-RR8M60FA87W-iJeRp2._adb-tls-connect._tcp shell "am broadcast -W --include-stopped-packages -a com.blazzgrabber.NOTIFY -p com.blazzgrabber --es type complete --es text 'Proses selesai' --es sound none"
```
Tanpa menunggu (hapus -W), bungkus dengan "" agar text panjang bisa masuk
```
adb -s adb-RR8M60FA87W-iJeRp2._adb-tls-connect._tcp shell "am broadcast --include-stopped-packages -a com.blazzgrabber.NOTIFY -p com.blazzgrabber --es type complete --es text 'Proses selesai' --es sound none"
```
Log notifikasi menggunakan tag `BlazzResponse` dengan marker `NOTIFY_TAMPIL` atau `NOTIFY_GAGAL`.

### Gambar thumbnail (opsional)

Extra `image` menerima URL gambar (`http`/`https`) untuk ditampilkan sebagai large icon notifikasi, misalnya poster video atau thumbnail hasil proses. Tanpa `image`, notifikasi tampil seperti sebelumnya: teks saja.

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.NOTIFY `
  -p com.blazzgrabber `
  --es type complete `
  --es text "Unduhan selesai" `
  --es image "https://cdn.example.com/thumb/x-123.jpg"
```

Gambar bersifat opsional dan tidak pernah menggagalkan notifikasi. Isinya diunduh ke memori lalu di-downsample ke sisi terpanjang 256 px; gambar tidak pernah disimpan sebagai file, sehingga tidak masuk galeri dan tidak mengubah `gallery_rank` file di `Download/BlazzMedia/`. Batas: timeout 3 detik untuk koneksi dan 3 detik untuk baca, ukuran maksimal 5 MB. Bila URL mati, HTTP gagal, timeout, body terlalu besar, atau isinya bukan gambar, notifikasi tetap tampil dengan teks saja dan logcat mencatat `NOTIFY_TANPA_GAMBAR alasan=...` (`HTTP_404`, `TERLALU_BESAR`, `BUKAN_GAMBAR`, dan sejenisnya).

Hasil unduhan disimpan di memori maksimal 8 URL, sehingga update `id` yang sama tidak mengunduh ulang. Kegagalan tidak dicoba ulang selama 30 detik. Tanpa extra `image`, aplikasi tidak membuka koneksi jaringan sama sekali.

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

`CEK_STATUS` juga menerima `ref` atau `filename` (selain `download_id`) dan membalas JSON:

```json
{"ok":true,"download_id":1234,"status":"RUNNING","bytes":10158080,"total":21107071,"reason":0,"filename":"1u-pje5j.mp4","indexed":false,"ref":"item-9812"}
```

`status`: `PENDING`, `RUNNING`, `PAUSED`, `SUCCESSFUL`, `FAILED`, `NOT_FOUND`. `indexed` berarti file sudah terdaftar di MediaStore sebagai video.

## Cek File dan Urutan Galeri

### `CEK_FILE`

Extra: `filename`. Mengembalikan keberadaan, ukuran, status MediaStore, dan posisi di galeri.

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.CEK_FILE `
  -p com.blazzgrabber `
  --es filename "1u-pje5j.mp4"
```

```json
{"ok":true,"exists":true,"size":21107071,"type":"video","in_mediastore":true,"date_added":1759476120,"date_modified":1759476120,"gallery_rank":0}
```

`type` bernilai `video` atau `image`. `gallery_rank: 0` berarti item pertama di galeri. Rank dihitung per jenis media: rank gambar diukur di antara gambar, rank video di antara video.

Bila file sudah ada tetapi MediaStore belum mengindeksnya (umum terjadi di Android 9 tepat setelah unduhan selesai karena scan MediaProvider berjalan asinkron), `CEK_FILE` memicu scan dan menunggu hingga sekitar 3 detik sebelum membalas. Dengan begitu pemeriksaan rank pertama sudah mengembalikan `gallery_rank`, tanpa perlu memanggil ulang.

### `TOUCH`

Extra: `filename`. Menjadikan file item pertama di galeri tanpa mengunduh ulang. Berlaku untuk video maupun gambar.

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.TOUCH `
  -p com.blazzgrabber `
  --es filename "1u-pje5j.mp4"
```

Respons: `{"ok":true,"gallery_rank":0}`; bila gagal: `{"ok":false,"error":"TOUCH_FAILED"}`.

Cara kerja: file dipindah sementara, baris MediaStore lama dihapus, file disalin kembali dengan nama yang sama sebagai file baru, lalu diindeks ulang lewat `ContentResolver.insert` di semua versi Android (Android 10+ di-finalize lewat `IS_PENDING`). Bila insert ditolak karena sudah ada baris `_data` yang sama, aplikasi memicu `MediaScannerConnection.scanFile` lalu menunggu baris itu terlihat. Dengan begitu file yang masuk lewat `adb push` dari komputer tetap bisa dipindah ke urutan 0, meskipun push meninggalkan baris pending milik proses lain. Bila file sudah berada di urutan 0, `TOUCH` langsung selesai tanpa menyalin.

### Baris MediaStore pending

File yang ditulis proses lain (mis. `adb push` dari komputer) bisa meninggalkan baris MediaStore `is_pending=1` milik proses itu. Baris seperti ini tidak terlihat aplikasi, sehingga `CEK_FILE` sempat membalas `in_mediastore:false` tanpa `gallery_rank` dan `TOUCH` gagal `RANK=-1`. Sekarang kedua action itu menyelesaikannya: baris pending milik aplikasi sendiri di-finalize lewat `IS_PENDING=0`, sedangkan baris milik proses lain dipicu ulang lewat `MediaScannerConnection.scanFile` lalu ditunggu sampai terlihat (maksimal sekitar 3 detik). Marker logcat: `INDEX_FINAL type=... id=...` ketika baris diselesaikan, dan `INDEX_GAGAL ... alasan=SQLiteConstraintException` ketika insert pertama ditolak sebelum pemulihan berhasil.

## Info Perangkat

### `INFO`

Tanpa extra. Untuk pemeriksaan perangkat dan verifikasi versi minimum.

```powershell
adb shell am broadcast -W --include-stopped-packages `
  -a com.blazzgrabber.INFO `
  -p com.blazzgrabber
```

```json
{"ok":true,"version_name":"1.1.0","version_code":2,"sdk":28,
 "perm":{"storage_write":true,"storage_read":true,"notifications":true,"manage_storage":false},
 "storage_free_mb":5120,"battery_pct":78,"media_dir":"/storage/emulated/0/Download/BlazzMedia"}
```

## Batalkan Unduhan dan Bersihkan

### `BATAL_UNDUH`

Extra: `download_id` atau `ref`. Membatalkan unduhan berjalan dan menghapus file parsial.

```json
{"ok":true,"cancelled":true}
```

### `CLEANUP`

Extra: `filename` atau `older_than_hours` (int). Hanya menghapus file video dan gambar di folder `BlazzMedia`.

```json
{"ok":true,"deleted":3,"freed_bytes":63321213}
```

## Kode Galat per Action

| Action | Kode galat |
|---|---|
| `UNDUH_VIDEO` | `BAD_URL`, `HOST_NOT_ALLOWED`, `BAD_ARGS`, `NO_SPACE`, `DOWNLOAD_FAILED`, `INTERNAL` |
| `CEK_STATUS` | `NOT_FOUND` (bila `download_id`/`ref`/`filename` tak dikenal), `INTERNAL` |
| `CEK_FILE` | `BAD_ARGS` (nama file tidak valid), `INTERNAL` |
| `TOUCH` | `TOUCH_FAILED`, `BAD_ARGS`, `INTERNAL` |
| `BATAL_UNDUH` | `NOT_FOUND`, `INTERNAL` |
| `CLEANUP` | `BAD_ARGS`, `INTERNAL` |
| `INFO` | `INTERNAL` |
| `NOTIFY` | Tidak ada respons JSON; kegagalan dicatat di logcat sebagai `NOTIFY_GAGAL` atau `NOTIFY_TANPA_GAMBAR` |

Catatan: `SIZE_MISMATCH` dan `NO_PERMISSION` dari rencana kontrak v0.1 **belum diimplementasikan**. Ukuran akhir unduhan tidak dibandingkan dengan `expected_size`, dan izin yang kurang dilaporkan lewat `INFO.perm.*`, bukan `NO_PERMISSION`.

## Jawaban Pertanyaan Kontrak v0.2 (bagian 3)

1. **`if_exists=skip` dengan ukuran berbeda:** file dianggap belum cocok, unduhan diteruskan (bukan `EXISTS`, dan belum ada `SIZE_MISMATCH`). `EXISTS` dikembalikan hanya bila `expected_size` kosong/0 atau sama persis.
2. **`if_exists=replace`:** file lama dihapus dari penyimpanan lebih dulu, lalu unduhan dimulai. Baris MediaStore lama tidak dihapus eksplisit.
3. **Ukuran akhir ≠ `expected_size`:** belum dilaporkan sebagai gagal (`SIZE_MISMATCH` belum ada); status tetap `SUCCESSFUL` bila DownloadManager sukses.
4. **Host setelah redirect:** belum diperiksa; hanya host pada URL awal yang divalidasi.
5. **`SUCCESSFUL` menjamin `indexed:true`?** Tidak. `indexed` dihitung saat `CEK_STATUS` dipanggil, dan marker `UNDUH_SELESAI` menandai selesainya unduhan DownloadManager, bukan pengindeksan MediaStore.
6. **Pemulihan file sementara `TOUCH`:** belum ada pemulihan otomatis. Bila proses mati setelah file dipindah ke `.` + nama + `.blazztouch` tetapi sebelum salinan selesai, file bisa tertinggal di lokasi sementara dan perlu dihapus manual.
7. **`EXISTS` menyertakan `size` dan `gallery_rank`?** Ya, sudah disertakan.
8. **Daftar kode galat tiap action:** lihat tabel [Kode Galat per Action](#kode-galat-per-action).
9. **`INFO` menyertakan konfigurasi build (`allow_http`, `allowed_hosts`)?** Belum; `INFO` tidak memuatnya.

---

## Hasil Uji (27-09-2026)

Uji end-to-end pertama, semua langkah via ADB-over-Tailscale ke Samsung a20 (SM-A205F, `100.104.18.71:5555`). Video uji: `https://get.blazz.biz.id/projects/sabun-cuci-piring/output/l3-1teox.mp4` (20,1 MB, HTTP 200, `Accept-Ranges: bytes`).

| Langkah | Hasil |
|---|---|
| `adb install -r apk/blazz-grabber.apk` (Streamed Install) | ✅ Success |
| `am set-standby-bucket com.blazzgrabber active` | ✅ ok |
| Broadcast `UNDUH_VIDEO` (`--include-stopped-packages`) | ✅ result=0, logcat `UNDUH_DIMULAI id=5177` |
| Progres `UNDUH_STATUS` | status=2, 10.158.080 / 21.107.071 bytes |
| Selesai | ✅ `UNDUH_SELESAI id=5177` — ~59 detik (≈356 KB/s, internet HP sendiri) |
| Verifikasi `/sdcard/Download/BlazzMedia/l3-1teox.mp4` | ✅ **21.107.071 bytes — exact match** dgn Content-Length server |

Kesimpulan: unduhan langsung di Android via DownloadManager **berfungsi penuh** — PC hanya mengirim URL (~ratusan byte) lewat tailnet, bukan isi video. Catatan teknis sesi ini:

- `am broadcast` tanpa `-W` tidak menampilkan `result=`, tapi broadcast tetap terkirim; `-W` hanya berguna utk konfirmasi sinkron.
- Perintah shell berawalan `/sdcard/...` dari git-bash harus dibungkus tanda kutip / `MSYS_NO_PATHCONV=1`, kalau tidak path di-mangle jadi `C:/Program Files/Git/sdcard/...`.

## Uji ASUS_X01AD (Android 9 / SDK 28) (27-09-2026)

Uji kompatibilitas di perangkat kedua. Device tersambung lewat tailnet (`100.94.147.80:5555`, WiFi HP sendiri); transport adb (USB vs tailnet) tidak mempengaruhi perilaku APK.

| Langkah | Hasil |
|---|---|
| `adb install -r apk/blazz-grabber.apk` | ✅ Success |
| Broadcast `UNDUH_VIDEO` | ❌ `UNDUH_GAGAL alasan=SecurityException` |
| Diagnosa logcat | DownloadManager menolak tulis `/storage/emulated/0/Download/BlazzMedia/` — **tidak punya `WRITE_EXTERNAL_STORAGE`** |
| `pm grant` WRITE + READ | ✅ grant-ok (APK tanpa activity → izin runtime harus di-grant adb) |
| Broadcast `UNDUH_VIDEO` ulang | ✅ `UNDUH_DIMULAI id=1695` → `UNDUH_SELESAI` ~110 detik (≈195 KB/s, WiFi HP) |
| Verifikasi `/sdcard/Download/BlazzMedia/l3-1teox.mp4` | ✅ **21.107.071 bytes — exact match** |

### Temuan penting: izin storage di Android 9 (targetSdk 35)

- APK manifest meminta `WRITE_EXTERNAL_STORAGE` + `READ_EXTERNAL_STORAGE`, tapi **targetSdk=35** membuat keduanya jadi izin runtime — di Android 9 (pre-scoped storage) `pm install` TIDAK meng-grant-nya otomatis.
- Di Android 11+ tidak masalah (scoped storage mengecualikan `Download/`), makanya a20 lancar tanpa grant.
- **Wajib setelah install di Android ≤ 10:**

```bash
adb -s <serial> shell pm grant com.blazzgrabber android.permission.WRITE_EXTERNAL_STORAGE
adb -s <serial> shell pm grant com.blazzgrabber android.permission.READ_EXTERNAL_STORAGE
```

Gejala kalau lupa: logcat `BlazzResponse: UNDUH_GAGAL alasan=SecurityException`, dan di log system muncul `DownloadManager: ... Neither user ... nor current process has android.permission.WRITE_EXTERNAL_STORAGE`.

Catatan: saat pengujian ini USB ASUS tidak tercolok (serial USB `JBAXGF02N4308CH` tak muncul di `adb devices`), jadi uji dijalankan via tailnet — hasilnya identik untuk sisi APK. Bila ingin murni USB, colok kabel lalu ulangi broadcast.

## Uji Sony SO-01H (docomo, Android 7.0 / SDK 24) via USB (27-09-2026)

Uji kompatibilitas perangkat ketiga, kali ini **murni USB** (serial `BH904ZG254`).

| Langkah | Hasil |
|---|---|
| `adb devices -l` | ✅ `BH904ZG254 device product:SO-01H` |
| `getprop ro.build.version.sdk` | 24 (Android 7.0, ≥ minSdk 23) |
| `adb install -r apk/blazz-grabber.apk` | ✅ Success |
| `pm grant` WRITE + READ (wajib Android ≤ 10) | ✅ grant-ok |
| Broadcast `UNDUH_VIDEO` | ✅ `UNDUH_DIMULAI id=3595` |
| Progres | ~50 KB/s (jaringan HP lambat — bukan APK), total ~5,5 mnt |
| Selesai | ✅ `UNDUH_SELESAI id=3595` |
| Verifikasi `/sdcard/Download/BlazzMedia/l3-1teox.mp4` | ✅ **21.107.071 bytes — exact match** |

Kesimpulan: BlazzGrabber **kompatibel Android 7, 9, dan 11** — alur install → grant → broadcast → unduh → file utuh identik di ketiganya. Untuk Android ≤ 10, `pm grant` WRITE/READ adalah langkah wajib setelah install (lihat temuan di atas).

## Uji Action JSON Baru (CEK_FILE / TOUCH / INFO / CEK_STATUS / BATAL_UNDUH / CLEANUP)

Action baru (balasan JSON lewat `setResultData`) diuji pada Samsung A20 (Android 11) dan ASUS X01AD (Android 9); semuanya lulus:

| Action | Hasil |
|---|---|
| `INFO` | ✅ `perm.*`, `version_code`, `sdk`, `storage_free_mb`, `battery_pct` terbaca |
| `CEK_FILE` | ✅ `exists`, `size`, `in_mediastore`, `gallery_rank` sesuai |
| `TOUCH` | ✅ `gallery_rank` dari 9 → 0 (Android 11 ~3,2 s; Android 9 ~0,78 s; menyalin ~21 MB) |
| `CEK_STATUS` | ✅ status `PENDING`/`RUNNING`/`SUCCESSFUL`/`FAILED`/`NOT_FOUND`, plus `indexed` |
| `UNDUH_VIDEO` `if_exists=skip` | ✅ `state:EXISTS` dengan `size` + `gallery_rank` |
| `BATAL_UNDUH` | ✅ `cancelled:true` |
| `CLEANUP` tanpa extra | ✅ `BAD_ARGS` |
| `HOST_NOT_ALLOWED` / `BAD_URL` | ✅ ditolak sesuai konfigurasi |
| Izin `DUMP` | ✅ shell/ADB bisa, app lain tidak |
| `CEK_FILE` gambar | ✅ `type:"image"`, `in_mediastore:true`, `gallery_rank:0` (Samsung, `gelang-1.jpeg`) |
| `TOUCH` gambar | ✅ `gallery_rank:0` |
| `CEK_FILE`/`TOUCH` gambar hasil `adb push` | ✅ Android 9 (ASUS): baris dibuat saat `CEK_FILE`, rank 0 → 1 setelah gambar baru, `TOUCH` → 0. Android 11 (Samsung): baris `is_pending=1` milik `com.android.shell` diselesaikan lewat scan, lalu `TOUCH` → 0 |
| `CEK_FILE` video (regresi) | ✅ `type:"video"`, rank dihitung di koleksi video |

Android 7 (Sony SO-01H): `INFO` sudah terverifikasi (`sdk:24`, `storage_write/read:true`); uji unduh/`TOUCH` **ditunda** karena koneksi WiFi perangkat sedang bermasalah (DownloadManager `connect timed out`). Akan diulang saat koneksi Sony normal.

---

