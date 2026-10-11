package com.shilapi.xcertplay.setup

import android.content.Context
import android.os.Build

/** The BYD head unit generation. The setup guide uses it only to explain which features apply. */
enum class DiLinkGeneration(val key: String) {
    DILINK_2("dilink2"),
    DILINK_3("dilink3"),
    DILINK_4("dilink4"),
    DILINK_5("dilink5"),
    UNKNOWN("unknown");

    /** How the guess was made, so the guide can say how sure it is. */
    enum class Source { SYSTEM_VERSION, LIKELY, NONE }

    data class Detection(val generation: DiLinkGeneration, val source: Source, val evidence: String?)

    companion object {
        private val VERSION = Regex("""DiLink\s*([2345])(?:\.\d+)?""", RegexOption.IGNORE_CASE)
        private const val CONTROLLER_VERSION_PROPERTY = "apps.setting.product.outswver"
        private const val BYD_DEVICE_MANAGER = "android.hardware.bydauto.BYDAutoDeviceManager"
        private const val PREFS = "diplay"
        private const val KEY = "setup_dilink_generation"

        fun fromKey(key: String?): DiLinkGeneration? = entries.firstOrNull { it.key == key }

        /**
         * BYD names the build after the head unit: DiLink 3.0 reports product `DiLink3.0` and a
         * `BYD-AUTO/DiLink3.0/...` fingerprint. DiLink 5.1 reports a generic `IVI` product on Android 13.
         * DiLink 2.0 runs Android 7.1 with Qualcomm's generic `msm8953_64` build, which other brands
         * ship too; only BYD's vehicle framework ([bydFramework]) tells it apart (#519).
         */
        fun detect(product: String?, fingerprint: String?, display: String?, sdkInt: Int,
                   controllerVersion: String? = null, bydFramework: Boolean = false): Detection {
            for (value in listOf(product, fingerprint, display)) {
                val match = value?.let(VERSION::find) ?: continue
                val generation = when (match.groupValues[1]) {
                    "2" -> DILINK_2
                    "3" -> DILINK_3
                    "4" -> DILINK_4
                    else -> DILINK_5
                }
                return Detection(generation, Source.SYSTEM_VERSION, value)
            }
            controllerVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { version ->
                val generation = when (version.substringBefore('.').toIntOrNull()) {
                    // DiLink 2.0/2.1 controllers, from the community version table cited in #516.
                    2, 4, 5, 8, 26 -> DILINK_2
                    13, 15, 18 -> DILINK_3
                    16, 17, 21 -> DILINK_4
                    23, 34, 57, 59 -> DILINK_5
                    else -> null
                }
                if (generation != null) return Detection(generation, Source.SYSTEM_VERSION, version)
            }
            // DiLink 3.0 and later run Android 9 or newer.
            if (bydFramework && sdkInt < 28) {
                return Detection(DILINK_2, Source.LIKELY, fingerprint?.takeIf { it.isNotBlank() })
            }
            if (fingerprint?.startsWith("BYD-AUTO/IVI/") == true && sdkInt >= 33) {
                return Detection(DILINK_5, Source.LIKELY, fingerprint)
            }
            return Detection(UNKNOWN, Source.NONE, fingerprint?.takeIf { it.isNotBlank() })
        }

        fun detect(): Detection = detect(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY,
            Build.VERSION.SDK_INT, readControllerVersion(), hasBydFramework())

        private fun hasBydFramework(): Boolean = runCatching { Class.forName(BYD_DEVICE_MANAGER) }.isSuccess

        private fun readControllerVersion(): String? = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, CONTROLLER_VERSION_PROPERTY, "")?.toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun confirmed(context: Context): DiLinkGeneration? = fromKey(prefs(context).getString(KEY, null))

        /** The driver's confirmed choice, or the detected generation when they have not picked one. */
        fun current(context: Context): DiLinkGeneration = confirmed(context) ?: detect().generation

        fun save(context: Context, generation: DiLinkGeneration) {
            prefs(context).edit().putString(KEY, generation.key).apply()
        }
    }
}
