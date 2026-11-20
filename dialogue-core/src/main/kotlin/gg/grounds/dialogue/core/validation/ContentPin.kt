package gg.grounds.dialogue.core.validation

@JvmRecord
data class ContentPin(val sha256: String) {
    init {
        require(SHA_256.matches(sha256)) { "Invalid SHA-256 pin" }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
