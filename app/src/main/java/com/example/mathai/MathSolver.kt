package com.example.mathai

object MathSolver {

    fun solve(raw: String): Boolean? {
        val text = raw
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace("–", "-")
            .replace(",", ".")
            .replace(" ", "")

        val match = Regex(
            """^([0-9().+\-*/]+)(>=|<=|>|<|==|=)([0-9().+\-*/]+)$"""
        ).find(text) ?: return null

        return try {
            val first = Parser(match.groupValues[1]).parse()
            val second = Parser(match.groupValues[3]).parse()

            when (match.groupValues[2]) {
                ">" -> first > second
                "<" -> first < second
                ">=" -> first >= second
                "<=" -> first <= second
                "=", "==" -> kotlin.math.abs(first - second) < 0.000001
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun parse(): Double {
            val result = expression()
            if (position != text.length) error("Invalid expression")
            return result
        }

        private fun expression(): Double {
            var result = term()

            while (position < text.length &&
                (text[position] == '+' || text[position] == '-')
            ) {
                val operator = text[position++]
                val number = term()

                result = if (operator == '+') {
                    result + number
                } else {
                    result - number
                }
            }

            return result
        }

        private fun term(): Double {
            var result = factor()

            while (position < text.length &&
                (text[position] == '*' || text[position] == '/')
            ) {
                val operator = text[position++]
                val number = factor()

                result = if (operator == '*') {
                    result * number
                } else {
                    result / number
                }
            }

            return result
        }

        private fun factor(): Double {
            if (position < text.length &&
                (text[position] == '+' || text[position] == '-')
            ) {
                val operator = text[position++]
                val number = factor()
                return if (operator == '-') -number else number
            }

            if (position < text.length && text[position] == '(') {
                position++
                val result = expression()

                if (position >= text.length || text[position++] != ')') {
                    error("Missing parenthesis")
                }

                return result
            }

            val start = position

            while (position < text.length &&
                (text[position].isDigit() || text[position] == '.')
            ) {
                position++
            }

            if (start == position) error("Number expected")

            return text.substring(start, position).toDouble()
        }
    }
}
