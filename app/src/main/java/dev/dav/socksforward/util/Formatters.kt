package dev.dav.socksforward.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, value, units[unit])
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.getDefault(), "%d ч %02d мин", hours, minutes)
        minutes > 0 -> String.format(Locale.getDefault(), "%d мин %02d с", minutes, seconds)
        else -> String.format(Locale.getDefault(), "%d с", seconds)
    }
}

/** Locale aware plain number for counters and port fields. */
fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%d", value)
