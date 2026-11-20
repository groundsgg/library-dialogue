package gg.grounds.dialogue.core.text

import java.util.regex.Pattern

class Graphemes private constructor() {
    companion object {
        private val cluster = Pattern.compile("\\X")

        @JvmStatic
        fun split(text: String): List<String> =
            cluster.matcher(text).results().map { it.group() }.toList()

        @JvmStatic
        fun reveal(text: String, count: Int): String {
            require(count >= 0) { "Negative reveal" }
            return split(text).take(count).joinToString("")
        }
    }
}
