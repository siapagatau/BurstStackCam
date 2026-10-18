# Burst Stack Cam

Aplikasi kamera Android native (Kotlin + Jetpack Compose + Camera2 + C++/NDK) yang mengambil
burst 5/8/10 frame dengan exposure terkunci, lalu meratakan (align) dan menumpuk (stack) frame
untuk mengurangi noise.

## Catatan jujur
- Mode **PNG/JPEG** memakai frame `YUV_420_888` yang hanya **8-bit**. Stacking mengurangi noise dan
  memberi gradasi lebih halus (hasil disimpan 16-bit), tetapi **bukan RAW sensor asli**.
- Mode **DNG (RAW)** hanya muncul bila perangkat punya capability RAW dan output `RAW_SENSOR` (CFA Bayer).
  Stacking dilakukan di domain RAW; alignment dibulatkan ke kelipatan 2 piksel agar pola Bayer tetap utuh
  (tanpa sub-pixel). Metadata DNG diambil dari frame pertama.
- Di Android 8–9 (API 26–28) `WRITE_EXTERNAL_STORAGE` diminta (dengan `maxSdkVersion=28`). Android 10+ hanya
  memakai MediaStore tanpa izin penyimpanan.
- Hanya kamera belakang utama yang dipakai. Layar dikunci portrait; orientasi foto mengikuti sensor orientasi perangkat.

## Pipeline
1. Burst: `TEMPLATE_STILL_CAPTURE`, exposure/ISO dikunci dari hasil preview terakhir (`AE_MODE_OFF`, jika ada
   `MANUAL_SENSOR`; kalau tidak: `AE_LOCK`), AWB lock, fokus dikunci di jarak lensa terakhir.
   Tiap `Image` langsung disalin ke memori native lalu ditutup.
2. Frame referensi = variance of Laplacian tertinggi (pada level piramida 1/2).
3. Alignment: piramida 4 level, blok 16x16, SAD coarse-to-fine, refinement sub-pixel (parabola) di level terbaik,
   medan gerak diinterpolasi bilinear antar tile.
4. Penolakan ghosting: bobot per-tile dari MAD setelah alignment (ambang adaptif terhadap noise) + bobot per-piksel.
5. Stacking: rata-rata terbobot float32, diproses per strip baris di beberapa thread.
6. **Tampilan iPhone (satu-satunya look, otomatis, selalu aktif)**: auto-exposure diukur dari subjek (bukan langit),
   local tone mapping ala Smart HDR (bayangan terangkat, highlight dilindungi), kurva-S lembut dengan hitam tetap pekat,
   clarity sangat tipis, sharpening halus dengan coring. Warna natural: white balance dari permukaan abu-abu, saturasi
   +7%, vibrance hanya untuk warna pudar, batas gamut lembut (hijau/kuning tidak jadi neon), highlight kehilangan warna
   (tanpa glare pink/kuning), langit sedikit lebih biru, daun dikoreksi ke hijau asli. Tanpa halo, bloom, vignette, fade, grain.
   Region langit/daun/tanah/kulit/abu-abu dikenali klasik (tanpa ML) untuk sentuhan per-objek. Hanya PNG/JPEG; DNG tetap RAW polos.
6b. Tombol galeri di layar kamera mengedit foto lama dengan look yang sama (`Pipeline.editPhoto()`).
7. Output: PNG 16-bit (streaming, tidak pernah menahan seluruh gambar di heap Java), JPEG q88, atau DNG.

## Build lokal
Butuh JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1.

    ./gradlew assembleDebug      # APK di app/build/outputs/apk/debug/
    ./gradlew assembleRelease    # APK di app/build/outputs/apk/release/

`gradle/wrapper/gradle-wrapper.jar` (biner) tidak disertakan di paket ini. Jalankan sekali
`gradle wrapper --gradle-version 8.7` untuk membuatnya dan commit hasilnya. Workflow GitHub Actions
tetap berjalan tanpa jar itu (memakai Gradle 8.7 yang dipasang di runner).

## Build lewat GitHub Actions
1. Buat repo kosong di GitHub, lalu push seluruh isi folder ini.
2. Buka tab **Actions** → workflow **Build APK** berjalan otomatis pada setiap push
   (atau klik **Run workflow** untuk memilih `release`/`debug`).
3. Setelah hijau, buka run tersebut → bagian **Artifacts** → unduh **BurstStackCam-apk** (zip berisi APK).

### Tanda tangan APK (opsional)
Tanpa secrets, APK ditandatangani dengan debug key (bisa langsung dipasang, tetapi key-nya berbeda per runner,
jadi update antar build bisa gagal "signature mismatch" → uninstall dulu). Untuk key tetap:

    keytool -genkeypair -v -keystore release.jks -alias burststack -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 release.jks     # macOS: base64 -i release.jks

Tambahkan di *Settings → Secrets and variables → Actions*:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Install
Salin APK ke ponsel, buka, izinkan "install dari sumber tidak dikenal", atau `adb install app-release.apk`.
Hasil ada di galeri, folder **Pictures/BurstStackCam**.

## UI
Tanpa layar pengaturan: semua otomatis (8 frame, alignment, look iPhone, output JPEG). UI edge-to-edge dengan panel "glass"
(tint transparan + kilap + tepi tipis). Catatan: panel ini tiruan glass, bukan blur latar sungguhan.
