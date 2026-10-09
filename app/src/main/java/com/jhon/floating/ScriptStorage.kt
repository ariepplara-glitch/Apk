
package com.jhon.floating

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ScriptStorage {
    private const val PREF = "jhon_scripts_v3"
    private const val KEY = "scripts"

    fun getAll(context: Context): MutableList<Script> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = sp.getString(KEY, null) ?: return mutableListOf(
            Script(name = "JHON FB Video v2.9", code = "// Tempel kode Tampermonkey lu di sini\nconsole.log('JHON ready');"),
            Script(name = "Auto Scroll FB", code = "setInterval(()=>window.scrollBy(0,300),2000);"),
            Script(name = "FB Dark Mode", code = "document.documentElement.style.filter='invert(1) hue-rotate(180deg)';")
        )
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Script>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Script(o.getString("id"), o.getString("name"), o.getString("code"), o.optBoolean("enabled", true)))
            }
            list
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveAll(context: Context, list: List<Script>) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("name", it.name)
            o.put("code", it.code)
            o.put("enabled", it.enabled)
            arr.put(o)
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }
}
