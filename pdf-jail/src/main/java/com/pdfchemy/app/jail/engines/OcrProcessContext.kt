package com.pdfchemy.app.jail.engines

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/** SDK bookkeeping stays in this isolated process; no host-private storage is opened. */
internal class OcrProcessContext(context: Context) : ContextWrapper(context) {
    private val preferences = mutableMapOf<String, SharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(preferences) {
        preferences.getOrPut(name) { EphemeralPreferences() }
    }
}

internal class EphemeralPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): MutableMap<String, *> = synchronized(this) { values.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }.toMutableMap() }
    override fun contains(key: String): Boolean = synchronized(this) { key in values }
    override fun getString(key: String, defValue: String?): String? = synchronized(this) { values[key] as String? ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(this) { (values[key] as Set<String>?)?.toMutableSet() ?: defValues?.toMutableSet() }
    override fun getInt(key: String, defValue: Int): Int = synchronized(this) { values[key] as Int? ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(this) { values[key] as Long? ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(this) { values[key] as Float? ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(this) { values[key] as Boolean? ?: defValue }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(this) { listeners.add(listener) } }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(this) { listeners.remove(listener) } }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String, value: Any?) = apply { changes[key] = value }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            val changed: List<String>
            val observers: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(this@EphemeralPreferences) {
                val previous = values.toMap()
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                changed = (previous.keys + values.keys).filter { previous[it] != values[it] }
                observers = listeners.toList()
                clear = false; changes.clear()
            }
            val notify = Runnable { changed.forEach { key -> observers.forEach { it.onSharedPreferenceChanged(this@EphemeralPreferences, key) } } }
            if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else Handler(Looper.getMainLooper()).post(notify)
            return true
        }
    }
}
