package gg.grounds.dialogue.core.definition

import java.math.BigDecimal

/** Safe scalar arguments. They never carry commands or executable formatting. */
sealed interface ArgumentValue {
    @JvmRecord data class Text(val value: String) : ArgumentValue

    class Number(value: BigDecimal) : ArgumentValue {
        private val value = value.stripTrailingZeros()

        fun value(): BigDecimal = value

        override fun equals(other: Any?): Boolean = other is Number && value == other.value

        override fun hashCode(): Int {
            var hash = value.hashCode()
            return hash
        }

        override fun toString(): String = "Number[value=$value]"
    }

    @JvmRecord data class Flag(val value: Boolean) : ArgumentValue

    enum class Nil : ArgumentValue {
        INSTANCE
    }

    companion object {
        @JvmStatic fun of(value: String): ArgumentValue = Text(value)

        @JvmStatic fun of(value: Boolean): ArgumentValue = Flag(value)

        @JvmStatic fun of(value: Long): ArgumentValue = Number(BigDecimal.valueOf(value))
    }
}
