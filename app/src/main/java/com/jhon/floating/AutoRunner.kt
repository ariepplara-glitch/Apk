package com.jhon.floating

import android.content.Context
import android.content.Intent
import kotlin.random.Random

/**
 * Alur posting video di aplikasi Facebook asli, satu post per putaran.
 * Label tombol dicocokkan versi Indonesia + Inggris. Kalau tampilan FB beda,
 * tekan tombol LOG di panel (dump layar) lalu tambahkan label yang sesuai di bawah.
 */
object AutoRunner {
    private const val PREF = "autopost"

    private val COMPOSER = listOf("Apa yang Anda pikirkan", "What's on your mind", "Buat postingan", "Create post")
    private val MEDIA = listOf("Foto/video", "Photo/video", "Video", "Foto", "Photo")
    private val NEXT = listOf("Berikutnya", "Selanjutnya", "Next", "Lanjut")
    private val POST = listOf("Posting", "Kirim", "Bagikan", "Post", "Share")

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun isRunning() = running

    fun total(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("total", 13)
    fun next(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("next", 1)
    fun setTotal(c: Context, v: Int) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("total", v.coerceAtLeast(1)).apply()
    private fun setNext(c: Context, v: Int) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("next", v).apply()
    fun reset(c: Context) = setNext(c, 1)

    fun stop() { running = false; thread?.interrupt() }

    fun start(c: Context, log: (String) -> Unit, onProgress: () -> Unit) {
        if (running) return
        val svc0 = FbAutomationService.instance
        if (svc0 == null) { log("⚠ Aksesibilitas belum aktif. Buka app → tombol AKTIFKAN AKSESIBILITAS."); return }
        val app = c.applicationContext
        running = true
        thread = Thread {
            try {
                openFacebook(app, log)
                while (running && next(app) <= total(app)) {
                    val n = next(app)
                    log("▶ Post $n/${total(app)} (video%02d.mp4)".format(n))
                    if (!doPost(n, log)) { log("■ Berhenti di post $n. Tekan MULAI LAGI untuk ulang post ini."); break }
                    setNext(app, n + 1); onProgress()
                    if (next(app) <= total(app) && running) {
                        val wait = Random.nextLong(25_000, 50_000)
                        log("… jeda ${wait / 1000}s"); Thread.sleep(wait)
                    }
                }
                if (next(app) > total(app)) log("✔ Semua ${total(app)} post selesai.")
            } catch (e: InterruptedException) {
                log("■ Dihentikan.")
            } catch (e: Exception) {
                log("✖ Error: ${e.message}")
            } finally { running = false; onProgress() }
        }.also { it.start() }
    }

    private fun openFacebook(c: Context, log: (String) -> Unit) {
        val svc = FbAutomationService.instance ?: return
        if (svc.isFacebookForeground()) return
        for (pkg in FbAutomationService.FB_PACKAGES) {
            val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i)
            log("Membuka $pkg …"); Thread.sleep(4000); return
        }
        log("⚠ Aplikasi Facebook tidak ditemukan.")
    }

    private fun waitClick(labels: List<String>, timeoutMs: Long = 12_000): Boolean {
        val svc = FbAutomationService.instance ?: return false
        val end = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < end) {
            if (svc.clickLabel(labels)) { Thread.sleep(1200); return true }
            Thread.sleep(500)
        }
        return false
    }

    private fun doPost(n: Int, log: (String) -> Unit): Boolean {
        val svc = FbAutomationService.instance ?: return false
        if (!waitClick(COMPOSER)) { log("✖ Kolom 'Apa yang Anda pikirkan?' tidak ketemu. Buka beranda FB dulu."); return false }
        if (!waitClick(MEDIA)) { log("✖ Tombol Foto/video tidak ketemu."); return false }

        // Pilih video ke-n di galeri (urutan tampilan, bukan nama file — nama file tidak terbaca oleh aksesibilitas).
        var tiles = svc.videoTiles()
        var tries = 0
        while (tiles.size < n && tries < 6 && running) { svc.scrollForward(); Thread.sleep(1000); tiles = svc.videoTiles(); tries++ }
        if (tiles.size < n) { log("✖ Hanya ${tiles.size} video terlihat di galeri, butuh urutan ke-$n."); return false }
        if (!svc.click(tiles[n - 1])) { log("✖ Gagal memilih video ke-$n."); return false }
        Thread.sleep(1200)

        waitClick(NEXT, 6_000)      // beberapa versi FB melewati langkah ini
        if (!waitClick(POST, 15_000)) { log("✖ Tombol Posting tidak ketemu."); return false }
        Thread.sleep(8_000)         // beri waktu upload mulai
        log("✔ Post $n terkirim.")
        return true
    }
}
