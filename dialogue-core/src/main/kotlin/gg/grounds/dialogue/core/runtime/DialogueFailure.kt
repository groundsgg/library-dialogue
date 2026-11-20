package gg.grounds.dialogue.core.runtime

open class DialogueFailure : RuntimeException {
    private val code: String

    constructor(code: String, message: String?) : super(message) {
        this.code = code
    }

    constructor(code: String, cause: Throwable?) : super(code, cause) {
        this.code = code
    }

    open fun code(): String = code
}
