package com.burststack.cam

import android.content.Context

enum class OutputFormat { PNG16, JPEG, RAW_DNG }

/** No user settings: everything is automatic (8-frame burst, alignment, iPhone-style light and color, JPEG). */
data class AppSettings(
    val frameCount: Int = 8,
    val format: OutputFormat = OutputFormat.JPEG,
    val align: Boolean = true,
    val toneMap: Boolean = true,
    val sharpen: Boolean = true,
    val look: Int = 0
) {
    @Suppress("UNUSED_PARAMETER")
    fun sanitized(rawSupported: Boolean): AppSettings = AppSettings()
}

@Suppress("UNUSED_PARAMETER")
class SettingsRepository(context: Context) {
    fun load(): AppSettings = AppSettings()
    fun save(s: AppSettings) {}
}
