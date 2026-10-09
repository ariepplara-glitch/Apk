
package com.jhon.floating

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var scripts = mutableListOf<Script>()
    private val REQ_NOTIF = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.scriptContainer)
        scripts = ScriptStorage.getAll(this)

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.btnStartBubble).setOnClickListener { checkPermissionAndStart() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshList() }

        refreshList()
    }

    private fun refreshList() {
        scripts = ScriptStorage.getAll(this)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // FIX #4: baca active_id yang tersimpan supaya UI bisa nunjukin script mana yang
        // sedang ditandai aktif (dulu ditulis tapi tidak pernah dibaca/dipakai di mana pun).
        val activeId = getSharedPreferences("active", MODE_PRIVATE).getString("active_id", null)
        scripts.forEachIndexed { index, script ->
            val v = inflater.inflate(R.layout.layout_item_script, container, false)
            val isActive = script.id == activeId
            v.findViewById<TextView>(R.id.txtName).text =
                if (isActive) "✅ ${script.name} (AKTIF)" else script.name
            v.findViewById<TextView>(R.id.txtCodePreview).text = script.code.take(80) + "..."
            v.findViewById<Button>(R.id.btnEdit).setOnClickListener { showEditDialog(script) }
            v.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Hapus script?")
                    .setMessage(script.name)
                    .setPositiveButton("Hapus") { _, _ ->
                        scripts.removeAt(index)
                        ScriptStorage.saveAll(this, scripts)
                        // kalau yang dihapus adalah script yang lagi aktif, bersihkan penandanya juga
                        if (isActive) {
                            getSharedPreferences("active", MODE_PRIVATE).edit().remove("active_id").apply()
                        }
                        refreshList()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            v.findViewById<Button>(R.id.btnRun).setOnClickListener {
                Toast.makeText(this, "Ditandai AKTIF: " + script.name + " (auto-inject saat FB kebuka di bubble)", Toast.LENGTH_SHORT).show()
                getSharedPreferences("active", MODE_PRIVATE).edit().putString("active_id", script.id).apply()
                refreshList()
            }
            container.addView(v)
        }
        findViewById<TextView>(R.id.txtCount).text = "${scripts.size} script"
    }

    private fun showEditDialog(existing: Script?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_script, null)
        val edtName = view.findViewById<EditText>(R.id.edtName)
        val edtCode = view.findViewById<EditText>(R.id.edtCode)
        if (existing != null) {
            edtName.setText(existing.name)
            edtCode.setText(existing.code)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Tambah Script Baru" else "Edit Script")
            .setView(view)
            .setPositiveButton("SIMPAN") { _, _ ->
                val name = edtName.text.toString().ifEmpty { "Untitled" }
                val code = edtCode.text.toString()
                if (existing == null) {
                    scripts.add(Script(name = name, code = code))
                } else {
                    existing.name = name
                    existing.code = code
                }
                ScriptStorage.saveAll(this, scripts)
                refreshList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 1234)
            return
        }
        // FIX #2 (versi kompatibel tanpa activity-ktx): minta izin notifikasi dulu di
        // Android 13+ pakai API lama ActivityCompat.requestPermissions, supaya tidak
        // butuh dependency androidx.activity:activity-ktx tambahan -- lebih aman kalau
        // di-build lewat tools selain Android Studio/Gradle yang belum tentu pull
        // dependency itu dengan benar. Hasilnya ditangani di onRequestPermissionsResult().
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        startFloating()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // granted atau tidak, bubble tetap jalan -- notifikasi cuma bonus, bukan syarat wajib.
    }

    private fun startFloating() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Bubble Manager Aktif!", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1234 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startFloating()
        }
    }
}
