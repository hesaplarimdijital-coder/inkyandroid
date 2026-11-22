package com.inkyandroid.core.symbols

import java.util.TreeMap

/**
 * A map that iterates in the order of a JavaScript object's own string keys, because upstream keeps
 * symbols, tags and the like in plain objects and lists them in that order: keys that are array indices
 * (`"0"`, `"17"`, but not `"017"`) first, in ascending numeric order, then every other key in the order it
 * was first set. Setting an existing key keeps its place.
 */
internal class JsObjectMap<V> : AbstractMap<String, V>() {
    private val indexKeys = TreeMap<Long, V>()
    private val otherKeys = LinkedHashMap<String, V>()

    fun put(key: String, value: V) {
        val index = arrayIndexOf(key)
        if (index != null) indexKeys[index] = value else otherKeys[key] = value
    }

    override val size: Int get() = indexKeys.size + otherKeys.size

    override fun get(key: String): V? {
        val index = arrayIndexOf(key)
        return if (index != null) indexKeys[index] else otherKeys[key]
    }

    override fun containsKey(key: String): Boolean = get(key) != null

    override val entries: Set<Map.Entry<String, V>>
        get() {
            val result = LinkedHashMap<String, V>(size * 2)
            for ((index, value) in indexKeys) result[index.toString()] = value
            result.putAll(otherKeys)
            return result.entries
        }

    private companion object {
        /** The key's value if it is an array index (canonical decimal below 2^32 - 1), else null. */
        fun arrayIndexOf(key: String): Long? {
            if (key.isEmpty() || key.length > 10) return null
            if (key.length > 1 && key[0] == '0') return null
            for (c in key) if (c !in '0'..'9') return null
            val value = key.toLong()
            return if (value < 4294967295L) value else null
        }
    }
}
