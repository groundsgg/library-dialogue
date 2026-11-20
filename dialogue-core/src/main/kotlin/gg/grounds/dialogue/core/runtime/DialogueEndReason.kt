package gg.grounds.dialogue.core.runtime

enum class DialogueEndReason {
    COMPLETED,
    CANCELLED,
    LOOKED_AWAY,
    OUT_OF_RANGE,
    DISCONNECTED,
    HOST_CHANGED,
    SUPERSEDED,
    HOST_CLOSED,
    FAILED,
}
