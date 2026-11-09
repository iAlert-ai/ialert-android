package ir.ialert.sdk

import java.util.concurrent.ConcurrentHashMap

/**
 * Key/value storage for the registration and dedupe ids. On Android, back it with
 * SharedPreferences (see README); [MemoryStorage] is for tests.
 */
public interface KeyValueStorage {
    public fun getString(key: String): String?
    public fun putString(key: String, value: String?)
}

public class MemoryStorage : KeyValueStorage {
    private val items = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = items[key]

    override fun putString(key: String, value: String?) {
        if (value == null) items.remove(key) else items[key] = value
    }
}
