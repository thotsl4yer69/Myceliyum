package com.example.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromList(value: List<String>?): String {
        return value?.joinToString("|||") ?: ""
    }

    @TypeConverter
    fun toList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split("|||").filter { it.isNotEmpty() }
    }
}
