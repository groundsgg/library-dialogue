package gg.grounds.dialogue.cli

import java.io.IOException
import java.util.Properties

@JvmRecord
internal data class VersionInfo(val toolVersion: String, val validatorVersion: String) {
    companion object {
        @JvmStatic
        fun current(): VersionInfo {
            val properties = Properties()
            try {
                val input =
                    VersionInfo::class.java.getResourceAsStream("version.properties")
                        ?: error("Missing build version")
                input.use(properties::load)
            } catch (failure: IOException) {
                throw IllegalStateException("Unreadable build version", failure)
            }
            val version = properties.getProperty("version")
            check(!version.isNullOrBlank()) { "Missing build version" }
            return VersionInfo(version, version)
        }
    }
}
