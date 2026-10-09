
package com.jhon.floating

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.content.ContextCompat

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var windowView: View
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isWindowOpen = false

    // FIX #5/#6: WebView dipisah dari windowView dan disimpan di sini (bukan dibuat ulang
    // dari layout tiap openWindow()) supaya:
    //  (a) saat user klik CLOSE, kita bisa benar-benar panggil destroy() -- dulu cuma
    //      windowManager.removeView(windowView) yang cuma melepas tampilan dari layar,
    //      sementara WebView (dan semua setInterval/timer script yang jalan di dalamnya,
    //      termasuk script auto-post) tetap hidup di memori selamanya -> bocor memori
    //      setiap kali window dibuka-tutup berulang, resiko OOM di sesi panjang.
    //  (b) saat user klik MINIMIZE, WebView TIDAK di-destroy -- karena tujuan utama bubble
    //      ini adalah membiarkan script otomatis terus jalan di background walau panel
    //      kontrolnya disembunyikan. Dulu minimize dan close sama-sama manggil closeWindow()
    //      yang itu artinya "minimize" diam-diam ikut membunuh WebView juga.
    private var persistentWebView: WebView? = null
    private var editingId: String? = null

    // Mode APP: panel kecil tanpa WebView, melayang di atas aplikasi Facebook asli.
    private var appPanelView: View? = null
    private var appPanelParams: WindowManager.LayoutParams? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotification()
    }

    private fun createNotification() {
        val channelId = "bubble_manager_channel"
        val channel = NotificationChannel(channelId, "Bubble Manager", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("JHON Bubble Manager Aktif")
            .setContentText("Tap bubble untuk gonta-ganti script")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        // FIX #1: foregroundServiceType="specialUse" dideklarasikan di manifest (API 34+ wajib).
        // startForeground di sini otomatis memakai tipe yang dideklarasikan di manifest.
        startForeground(1, notif)
    }

    private fun createBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 400 }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams!!.x
                    initialY = bubbleParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    bubbleParams!!.x = initialX + dx
                    bubbleParams!!.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleWindow(); true }
                else -> false
            }
        }
        windowManager.addView(bubbleView, bubbleParams)
    }

    // FIX #6: tap bubble sekarang MINIMIZE (sembunyikan tanpa mematikan WebView),
    // bukan CLOSE penuh -- supaya tap tak sengaja tidak menghentikan script yang lagi jalan.
    private fun toggleWindow() {
        when {
            isWindowOpen -> minimizeWindow()      // panel WEB lama sedang terbuka
            appPanelView != null -> closeAppPanel() // panel app FB sedang terbuka
            else -> openAppPanel()                  // default: panel melayang di atas app FB
        }
    }

    private fun openWindow() {
        if (isWindowOpen) return
        windowView = LayoutInflater.from(this).inflate(R.layout.layout_floating_window_manager, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        windowParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.78).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val btnClose = windowView.findViewById<View>(R.id.btnClose)
        val btnMinimize = windowView.findViewById<View>(R.id.btnMinimize)
        val tabBrowser = windowView.findViewById<Button>(R.id.tabBrowser)
        val tabScripts = windowView.findViewById<Button>(R.id.tabScripts)
        val browserContainer = windowView.findViewById<LinearLayout>(R.id.browserContainer)
        val scriptsContainer = windowView.findViewById<View>(R.id.scriptsContainer)
        val scriptListContainer = windowView.findViewById<LinearLayout>(R.id.scriptListContainer)
        val edtScriptName = windowView.findViewById<EditText>(R.id.edtScriptName)
        val edtScriptCode = windowView.findViewById<EditText>(R.id.edtScriptCode)
        val btnSaveScript = windowView.findViewById<Button>(R.id.btnSaveScript)
        val btnNewScript = windowView.findViewById<Button>(R.id.btnNewScript)

        // FIX #5/#6: pakai WebView yang sudah ada (kalau tadinya cuma di-minimize) supaya
        // session/navigasi/script yang lagi jalan tidak ter-reset. Buat baru HANYA kalau
        // belum pernah ada atau sudah di-destroy() lewat tombol Close.
        val placeholderWebView = windowView.findViewById<WebView>(R.id.webView)
        browserContainer.removeView(placeholderWebView)
        val webView = persistentWebView ?: WebView(this).also { wv ->
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // FIX #4: auto-inject script yang ditandai "AKTIF" dari MainActivity.
                    // Dulu MainActivity cuma menulis active_id ke SharedPreferences, tapi
                    // tidak ada kode mana pun yang membacanya -- jadi tombol "AKTIF" tidak
                    // berefek apa-apa, user harus selalu INJECT manual tiap buka halaman.
                    val activeId = getSharedPreferences("active", MODE_PRIVATE).getString("active_id", null)
                    if (activeId != null) {
                        val script = ScriptStorage.getAll(this@FloatingService).find { it.id == activeId }
                        if (script != null) view?.evaluateJavascript(script.code, null)
                    }
                }
            }
            wv.loadUrl("https://m.facebook.com/")
            persistentWebView = wv
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        browserContainer.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header drag
        val header = windowView.findViewById<View>(R.id.header)
        var initX = 0
        var initY = 0
        var initTouchX = 0f
        var initTouchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = windowParams!!.x
                    initY = windowParams!!.y
                    initTouchX = event.rawX
                    initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams!!.x = initX + (event.rawX - initTouchX).toInt()
                    windowParams!!.y = initY + (event.rawY - initTouchY).toInt()
                    windowManager.updateViewLayout(windowView, windowParams)
                    true
                }
                else -> false
            }
        }

        fun showBrowser() {
            browserContainer.visibility = View.VISIBLE
            scriptsContainer.visibility = View.GONE
            tabBrowser.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            tabScripts.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
        fun showScripts() {
            browserContainer.visibility = View.GONE
            scriptsContainer.visibility = View.VISIBLE
            tabScripts.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            tabBrowser.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            loadScriptsInBubble(scriptListContainer, webView, edtScriptName, edtScriptCode, btnSaveScript)
        }

        tabBrowser.setOnClickListener { showBrowser() }
        tabScripts.setOnClickListener { showScripts() }
        btnNewScript.setOnClickListener {
            // FIX #3: "BARU" harus keluar dari mode edit, kalau tidak klik SIMPAN sesudahnya
            // akan nimpa script yang sebelumnya lagi di-edit, bukan bikin yang baru.
            editingId = null
            btnSaveScript.text = "SIMPAN SCRIPT"
            edtScriptName.setText("Script Baru")
            edtScriptCode.setText("// Tempel kode JS di sini\nconsole.log('hello');")
        }
        btnSaveScript.setOnClickListener {
            val name = edtScriptName.text.toString().ifEmpty { "Untitled" }
            val code = edtScriptCode.text.toString()
            val list = ScriptStorage.getAll(this)
            val curEditingId = editingId
            // FIX #3: dulu SELALU list.add(...) walau sedang mode edit (editingId sudah keisi
            // tapi tidak pernah dicek) -- hasilnya klik EDIT lalu SIMPAN bikin DUPLIKAT, bukan
            // menimpa script lama. Sekarang: kalau sedang edit, cari by id lalu timpa in place.
            val target = if (curEditingId != null) list.find { it.id == curEditingId } else null
            if (target != null) {
                target.name = name
                target.code = code
            } else {
                list.add(Script(name = name, code = code))
            }
            ScriptStorage.saveAll(this, list)
            editingId = null
            btnSaveScript.text = "SIMPAN SCRIPT"
            Toast.makeText(this, (if (target != null) "Script diupdate: " else "Script disimpan: ") + name, Toast.LENGTH_SHORT).show()
            loadScriptsInBubble(scriptListContainer, webView, edtScriptName, edtScriptCode, btnSaveScript)
        }

        // FIX #6: Close = matikan semuanya sungguhan. Minimize = cuma sembunyikan,
        // WebView dan script yang lagi jalan di dalamnya tetap hidup di background.
        btnClose.setOnClickListener { fullyCloseWindow() }
        btnMinimize.setOnClickListener { minimizeWindow() }

        windowManager.addView(windowView, windowParams)
        isWindowOpen = true
        showBrowser()
    }

    private fun loadScriptsInBubble(
        container: LinearLayout,
        webView: WebView,
        edtName: EditText,
        edtCode: EditText,
        saveBtn: Button
    ) {
        container.removeAllViews()
        val scripts = ScriptStorage.getAll(this)
        val inflater = LayoutInflater.from(this)
        scripts.forEach { script ->
            val v = inflater.inflate(R.layout.layout_item_script_bubble, container, false)
            v.findViewById<TextView>(R.id.txtName).text = script.name
            v.findViewById<Button>(R.id.btnInject).setOnClickListener {
                webView.evaluateJavascript(script.code, null)
                Toast.makeText(this, "Injected: ${script.name}", Toast.LENGTH_SHORT).show()
            }
            v.findViewById<Button>(R.id.btnEdit).setOnClickListener {
                edtName.setText(script.name)
                edtCode.setText(script.code)
                editingId = script.id
                saveBtn.text = "UPDATE SCRIPT"
                Toast.makeText(this, "Edit mode: ${script.name} - klik UPDATE SCRIPT untuk simpan", Toast.LENGTH_LONG).show()
            }
            v.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                val newList = scripts.filter { it.id != script.id }
                ScriptStorage.saveAll(this, newList)
                if (editingId == script.id) {
                    // batalkan mode edit kalau script yang lagi diedit itu yang dihapus
                    editingId = null
                    saveBtn.text = "SIMPAN SCRIPT"
                    edtName.setText("")
                    edtCode.setText("")
                }
                loadScriptsInBubble(container, webView, edtName, edtCode, saveBtn)
                Toast.makeText(this, "Dihapus", Toast.LENGTH_SHORT).show()
            }
            container.addView(v)
        }
    }

    // FIX #6: sembunyikan panel tapi JANGAN destroy WebView -- script yang lagi jalan
    // (mis. auto-post video) tetap hidup di background sampai user eksplisit klik Close.
    private fun minimizeWindow() {
        if (!isWindowOpen) return
        persistentWebView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        try { windowManager.removeView(windowView) } catch (e: Exception) {}
        isWindowOpen = false
    }

    // FIX #5: close sungguhan -- hentikan WebView (stopLoading + destroy) supaya semua JS/timer
    // di dalamnya (termasuk script yang pakai setInterval) benar-benar berhenti dan memorinya
    // dilepas. Dulu cuma removeView(windowView) tanpa destroy() -> bocor memori tiap buka-tutup.
    private fun fullyCloseWindow() {
        if (isWindowOpen) {
            try { windowManager.removeView(windowView) } catch (e: Exception) {}
            isWindowOpen = false
        }
        persistentWebView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        persistentWebView = null
    }

    // ===== MODE APP FB (tanpa WebView) =====
    private fun openAppPanel() {
        if (appPanelView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.layout_app_panel, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        // NOT_FOCUSABLE: sentuhan di luar panel tetap sampai ke Facebook; keyboard ikut mati,
        // jadi kolom "Total post" memakai FLAG khusus saat difokuskan (lihat bawah).
        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.80).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 200 }
        appPanelView = v; appPanelParams = p

        val txtLog = v.findViewById<TextView>(R.id.txtLog)
        val logScroll = v.findViewById<ScrollView>(R.id.logScroll)
        val edtTotal = v.findViewById<EditText>(R.id.edtTotal)
        val logFn: (String) -> Unit = { m ->
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            ui.post { txtLog.append("[$t] $m\n"); logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }
        }
        fun refresh() = ui.post {
            val n = AutoRunner.next(this); val tot = AutoRunner.total(this)
            v.findViewById<TextView>(R.id.txtCounter).text = "${(n - 1).coerceAtLeast(0)}/$tot"
            v.findViewById<TextView>(R.id.txtNext).text =
                if (n > tot) "SELESAI ✔  $tot / $tot" else "BERIKUTNYA %02d / %d  ·  video%02d.mp4".format(n, tot, n)
            v.findViewById<Button>(R.id.btnStart).text = if (AutoRunner.isRunning()) "⏳ BERJALAN…" else if (n > 1 && n <= tot) "▶ MULAI LAGI" else "▶ MULAI"
        }
        edtTotal.setText(AutoRunner.total(this).toString())
        refresh()

        // Ketuk kolom total -> izinkan keyboard sebentar; selesai edit -> kembali NOT_FOCUSABLE.
        edtTotal.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowManager.updateViewLayout(v, p)
                edtTotal.requestFocus()
            }
            false
        }
        edtTotal.setOnEditorActionListener { _, _, _ ->
            edtTotal.text.toString().toIntOrNull()?.let { AutoRunner.setTotal(this, it) }
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(v, p); refresh(); false
        }

        v.findViewById<Button>(R.id.btnStart).setOnClickListener {
            edtTotal.text.toString().toIntOrNull()?.let { AutoRunner.setTotal(this, it) }
            AutoRunner.start(this, logFn) { refresh() }; refresh()
        }
        v.findViewById<Button>(R.id.btnStop).setOnClickListener { AutoRunner.stop(); logFn("STOP ditekan"); refresh() }
        v.findViewById<Button>(R.id.btnResetRun).setOnClickListener {
            if (!AutoRunner.isRunning()) { AutoRunner.reset(this); logFn("Reset ke post 1"); refresh() }
        }
        v.findViewById<Button>(R.id.btnDump).setOnClickListener {
            val svc = FbAutomationService.instance
            logFn(if (svc == null) "⚠ Aksesibilitas belum aktif (buka app → AKTIFKAN AKSESIBILITAS)" else "Layar sekarang:\n" + svc.dumpScreen())
        }
        v.findViewById<View>(R.id.btnAppMin).setOnClickListener { closeAppPanel() }
        v.findViewById<View>(R.id.btnAppClose).setOnClickListener { AutoRunner.stop(); closeAppPanel() }
        v.findViewById<View>(R.id.btnAppWeb).setOnClickListener { closeAppPanel(); openWindow() }

        var iX = 0; var iY = 0; var tX = 0f; var tY = 0f
        v.findViewById<View>(R.id.appHeader).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { iX = p.x; iY = p.y; tX = e.rawX; tY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { p.x = iX + (e.rawX - tX).toInt(); p.y = iY + (e.rawY - tY).toInt()
                    windowManager.updateViewLayout(v, p); true }
                else -> false
            }
        }
        windowManager.addView(v, p)
    }

    // Minimize: panel hilang, tapi AutoRunner (thread terpisah) tetap jalan.
    private fun closeAppPanel() {
        appPanelView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        appPanelView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager.removeView(bubbleView) } catch (e: Exception) {}
        try { if (isWindowOpen) windowManager.removeView(windowView) } catch (e: Exception) {}
        AutoRunner.stop(); closeAppPanel()
        // FIX #5: pastikan WebView ikut mati total saat service-nya sendiri dihentikan
        // (mis. di-kill sistem atau user stop dari notifikasi), bukan cuma window-nya.
        persistentWebView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        persistentWebView = null
    }
}
