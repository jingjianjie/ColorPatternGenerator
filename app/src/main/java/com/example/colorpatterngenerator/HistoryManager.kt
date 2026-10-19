package com.example.colorpatterngenerator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class PatternState(
    val name: String,
    val info: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HistoryManager(context: Context) {
    private val history = ArrayList<PatternState>()
    private var currentIndex = -1
    private val MAX_SIZE = 100
    private val prefs: SharedPreferences = context.getSharedPreferences("pattern_history", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "history_list"
    private val KEY_INDEX = "history_index"

    init {
        loadFromDisk()
    }

    fun saveState(name: String, info: String) {
        // Avoid duplicate consecutive states
        if (currentIndex >= 0 && currentIndex < history.size) {
            val current = history[currentIndex]
            if (current.name == name && current.info == info) return
        }

        // If we are in the middle of history, truncate future
        while (history.size > currentIndex + 1) {
            history.removeAt(history.size - 1)
        }

        history.add(PatternState(name, info))
        if (history.size > MAX_SIZE) {
            history.removeAt(0)
        } else {
            currentIndex++ // Increment if not maxed out or after shift?
            // If we removed at 0, size stays same, index should decrement?
            // Let's keep it simple: currentIndex always points to last added
            currentIndex = history.size - 1
        }
        
        FileLogger.log("History", "Saved: $name. Size: ${history.size}, Index: $currentIndex")
        saveToDisk()
    }

    fun undo(): PatternState? {
        if (currentIndex > 0) {
            currentIndex--
            val state = history[currentIndex]
            FileLogger.log("History", "Undo to: ${state.name} ($currentIndex)")
            saveToDisk()
            return state
        }
        return null
    }

    fun redo(): PatternState? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            val state = history[currentIndex]
            FileLogger.log("History", "Redo to: ${state.name} ($currentIndex)")
            saveToDisk()
            return state
        }
        return null
    }
    
    fun getCurrentState(): PatternState? {
        if (currentIndex >= 0 && currentIndex < history.size) {
            return history[currentIndex]
        }
        return null
    }

    private fun saveToDisk() {
        try {
            val jsonArray = JSONArray()
            for (state in history) {
                val obj = JSONObject()
                obj.put("name", state.name)
                obj.put("info", state.info)
                obj.put("ts", state.timestamp)
                jsonArray.put(obj)
            }
            prefs.edit()
                .putString(KEY_HISTORY, jsonArray.toString())
                .putInt(KEY_INDEX, currentIndex)
                .apply()
        } catch (e: Exception) {
            FileLogger.e("History", "Save failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return
            val jsonArray = JSONArray(jsonStr)
            history.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                history.add(PatternState(
                    obj.getString("name"),
                    obj.getString("info"),
                    obj.getLong("ts")
                ))
            }
            currentIndex = prefs.getInt(KEY_INDEX, history.size - 1)
            // Bounds check
            if (currentIndex >= history.size) currentIndex = history.size - 1
            if (currentIndex < 0 && history.isNotEmpty()) currentIndex = 0
            
            FileLogger.log("History", "Loaded ${history.size} items. Index: $currentIndex")
        } catch (e: Exception) {
            FileLogger.e("History", "Load failed", e)
        }
    }
}
