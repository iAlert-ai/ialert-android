package ir.ialert.sdk

import android.content.Context
import android.content.SharedPreferences

/** [KeyValueStorage] on SharedPreferences; what [IAlert.start] uses. */
public class SharedPreferencesStorage(context: Context, name: String = "ialert") : KeyValueStorage {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
