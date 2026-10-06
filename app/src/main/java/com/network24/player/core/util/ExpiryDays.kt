package com.network24.player.core.util

import java.util.Calendar

/** Days left on the plan as calendar days (expiry 15 Oct seen on 6 Oct = 9), the same number on every screen. */
object ExpiryDays {
    fun from(expiryMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        fun day(ms: Long) = Calendar.getInstance().apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (expiryMs <= nowMs) return 0
        return Math.round((day(expiryMs) - day(nowMs)) / 86_400_000.0)
    }
}
