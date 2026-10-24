package com.example.absensiku   // GANTI sesuai package project Anda

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // GANTI dengan alamat hosting index.html (harus HTTPS, sama dengan URL_HOSTING di index.html)
        private const val START_URL = "https://absensi-cc9a7.web.app/"
    }

    private lateinit var webView: WebView
    private var pendingPermission: PermissionRequest? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (granted) req.grant(req.resources) else req.deny()
            }
            if (!granted) {
                Toast.makeText(this, "Izin kamera diperlukan untuk selfie absen", Toast.LENGTH_LONG).show()
            }
        }

    // Izin lokasi (untuk navigator.geolocation di halaman web)
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasil ->
            val granted = hasil[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                hasil[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
            if (!granted) {
                Toast.makeText(this, "Izin lokasi diperlukan untuk mencatat titik koordinat absen", Toast.LENGTH_LONG).show()
            }
        }

    private fun lokasiSudahDiizinkan(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Beri jarak dari status bar / navigation bar / keyboard
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // wajib: Firebase Auth menyimpan sesi di sini
            setGeolocationEnabled(true)              // izinkan navigator.geolocation (titik koordinat)
            mediaPlaybackRequiresUserGesture = false // preview kamera
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                return if (host == Uri.parse(START_URL).host) {
                    false // tetap di dalam aplikasi
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)) // link luar -> browser
                    true
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = """
                        <html><body style="font-family:sans-serif;text-align:center;padding:40px;color:#0f172a">
                        <h2>Tidak ada koneksi</h2>
                        <p>Periksa internet Anda, lalu coba lagi.</p>
                        <button style="padding:12px 24px;font-size:16px" onclick="location.href='$START_URL'">Coba Lagi</button>
                        </body></html>
                    """.trimIndent()
                    view.loadDataWithBaseURL(START_URL, html, "text/html", "UTF-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    // Hanya izinkan untuk halaman milik aplikasi ini
                    if (request.origin.host != Uri.parse(START_URL).host) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val butuhKamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!butuhKamera) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingPermission = request
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            // Permintaan lokasi dari navigator.geolocation
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                runOnUiThread {
                    // Hanya untuk halaman milik aplikasi ini
                    if (Uri.parse(origin).host != Uri.parse(START_URL).host) {
                        callback.invoke(origin, false, false)
                        return@runOnUiThread
                    }
                    if (lokasiSudahDiizinkan()) {
                        callback.invoke(origin, true, false)
                    } else {
                        // Jika ada permintaan sebelumnya yang belum selesai, tolak dulu
                        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
                        pendingGeoOrigin = origin
                        pendingGeoCallback = callback
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            }

            override fun onGeolocationPermissionsHidePrompt() {
                pendingGeoCallback = null
                pendingGeoOrigin = null
            }
        }

        // Tombol Back: biarkan halaman menangani dulu (tutup modal / kembali ke dashboard), baru keluar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("(window.handleBack && window.handleBack()) === true") { hasil ->
                    if (hasil != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            // Jika dibuka dari link QR (App Links), pakai alamat tersebut; kalau tidak, halaman utama
            val dataUri = intent?.data
            webView.loadUrl(dataUri?.toString() ?: START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    // ===== Cek pembaruan aplikasi (membaca version.json di hosting) =====
    private var dialogUpdate: AlertDialog? = null

    override fun onResume() {
        super.onResume()
        cekVersiAplikasi()
    }

    private fun versiTerpasang(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    private fun cekVersiAplikasi() {
        if (dialogUpdate?.isShowing == true) return
        Thread {
            try {
                val conn = URL(START_URL + "version.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()

                val terbaru = json.getInt("versionCode")
                val minimal = json.optInt("minVersionCode", terbaru)
                val apkUrl = json.optString("apkUrl")
                val terpasang = versiTerpasang()
                if (terpasang < terbaru && apkUrl.isNotEmpty()) {
                    val wajib = terpasang < minimal
                    runOnUiThread { tampilkanDialogUpdate(apkUrl, json.optString("versionName"), wajib) }
                }
            } catch (e: Exception) {
                // Gagal cek (offline dsb): abaikan, aplikasi tetap berjalan
            }
        }.start()
    }

    private fun tampilkanDialogUpdate(apkUrl: String, versionName: String, wajib: Boolean) {
        if (isFinishing || isDestroyed || dialogUpdate?.isShowing == true) return
        val builder = AlertDialog.Builder(this)
            .setTitle("Pembaruan tersedia")
            .setMessage(
                (if (versionName.isNotEmpty()) "Versi $versionName sudah tersedia. " else "") +
                    if (wajib) "Anda harus memperbarui aplikasi untuk melanjutkan."
                    else "Perbarui sekarang untuk mendapatkan fitur terbaru."
            )
            .setCancelable(!wajib)
            .setPositiveButton("Perbarui", null)
        if (!wajib) builder.setNegativeButton("Nanti", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))   // unduh APK terbaru
                if (!wajib) dialog.dismiss()                                   // jika wajib, dialog tetap tampil
            }
        }
        dialogUpdate = dialog
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Dipanggil dari JavaScript: AndroidBridge.simpanCsv(namaFile, isiCsv) */
    inner class AndroidBridge {
        /** Dipanggil dari JavaScript: AndroidBridge.versionCode() */
        @JavascriptInterface
        fun versionCode(): Int = versiTerpasang()

        @JavascriptInterface
        fun simpanCsv(namaFile: String, isi: String) {
            val aman = namaFile.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val pesan = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, aman)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Gagal membuat file")
                    contentResolver.openOutputStream(uri)?.use { it.write(isi.toByteArray(Charsets.UTF_8)) }
                    "Tersimpan di folder Download: $aman"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    File(dir, aman).writeText(isi, Charsets.UTF_8)
                    "Tersimpan di: ${File(dir, aman).absolutePath}"
                }
            } catch (e: Exception) {
                "Gagal menyimpan CSV: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this@MainActivity, pesan, Toast.LENGTH_LONG).show() }
        }
    }
}
