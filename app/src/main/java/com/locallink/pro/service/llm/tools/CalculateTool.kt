package com.locallink.pro.service.llm.tools

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class CalculateTool @Inject constructor() : ToolHandler {

    override val name: String = "calculate"

    override val description: String =
        "Evaluate a basic arithmetic expression. Supports + - * / % , parentheses, " +
            "decimals, and percentages like \"18% of 240\" (-> 0.18*240) or \"240 + 10%\". " +
            "Returns the numeric result."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("expression", JSONObject().apply {
                put("type", "string")
                put("description", "The arithmetic expression to evaluate, e.g. \"(2 + 3) * 4\" or \"18% of 240\".")
            })
        })
        put("required", org.json.JSONArray().apply { put("expression") })
    }.toString()

    override val readOnly: Boolean = true

    override suspend fun execute(args: JSONObject): String {
        val raw = args.optString("expression", "").trim()
        if (raw.isEmpty()) {
            return "Error: missing 'expression' argument."
        }
        return try {
            val result = Evaluator(raw).evaluate()
            if (result.isNaN()) {
                "Error: expression evaluated to NaN (e.g. 0/0 or invalid operation)."
            } else if (result.isInfinite()) {
                "Error: expression evaluated to infinity (e.g. division by zero)."
            } else {
                formatNumber(result)
            }
        } catch (e: ArithmeticException) {
            "Error: ${e.message ?: "arithmetic error"}."
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message ?: "invalid expression"}."
        } catch (e: Exception) {
            "Error: could not evaluate expression: ${e.message ?: e.javaClass.simpleName}."
        }
    }

    private fun formatNumber(value: Double): String {
        // Render integers without a trailing ".0"; otherwise trim spurious trailing zeros.
        if (value == value.toLong().toDouble()) {
            return value.toLong().toString()
        }
        var s = java.math.BigDecimal(value)
            .round(java.math.MathContext(12))
            .stripTrailingZeros()
            .toPlainString()
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return s
    }

    /**
     * Self-contained recursive-descent arithmetic evaluator.
     *
     * Grammar (after percentage preprocessing):
     *   expression := term (('+' | '-') term)*
     *   term       := factor (('*' | '/' | '%') factor)*
     *   factor     := ('+' | '-') factor | primary
     *   primary    := number | '(' expression ')'
     *
     * Percentage handling is done as a textual preprocessing pass before tokenizing:
     *   "X% of Y"  -> "(X/100)*(Y)"
     *   "Y + X%"   -> handled by treating a standalone trailing '%' on a number as "/100".
     * To keep it simple and predictable, any "N%" not followed by "of" is rewritten to "(N/100)".
     */
    private class Evaluator(input: String) {

        private val expr: String = normalize(input)
        private var pos: Int = 0

        fun evaluate(): Double {
            val value = parseExpression()
            skipWhitespace()
            if (pos < expr.length) {
                throw IllegalArgumentException("unexpected character '${expr[pos]}' at position $pos")
            }
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                val c = peek()
                if (c == '+') {
                    pos++
                    value += parseTerm()
                } else if (c == '-') {
                    pos++
                    value -= parseTerm()
                } else {
                    break
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                val c = peek()
                if (c == '*') {
                    pos++
                    value *= parseFactor()
                } else if (c == '/') {
                    pos++
                    val divisor = parseFactor()
                    value /= divisor
                } else if (c == '%') {
                    // Modulo operator (percentages were already rewritten in normalize()).
                    pos++
                    val divisor = parseFactor()
                    value %= divisor
                } else {
                    break
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            val c = peek()
            if (c == '+') {
                pos++
                return parseFactor()
            }
            if (c == '-') {
                pos++
                return -parseFactor()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            val c = peek()
            if (c == '(') {
                pos++
                val value = parseExpression()
                skipWhitespace()
                if (peek() != ')') {
                    throw IllegalArgumentException("missing closing parenthesis")
                }
                pos++
                return value
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipWhitespace()
            val start = pos
            var seenDot = false
            if (peek() == '.') {
                seenDot = true
                pos++
            }
            while (pos < expr.length) {
                val ch = expr[pos]
                if (ch in '0'..'9') {
                    pos++
                } else if (ch == '.' && !seenDot) {
                    seenDot = true
                    pos++
                } else {
                    break
                }
            }
            if (pos == start || (pos == start + 1 && seenDot)) {
                val found = if (pos < expr.length) "'${expr[pos]}'" else "end of input"
                throw IllegalArgumentException("expected a number but found $found at position $start")
            }
            val token = expr.substring(start, pos)
            return token.toDoubleOrNull()
                ?: throw IllegalArgumentException("invalid number '$token'")
        }

        private fun peek(): Char {
            return if (pos < expr.length) expr[pos] else '\u0000'
        }

        private fun skipWhitespace() {
            while (pos < expr.length && expr[pos].isWhitespace()) {
                pos++
            }
        }

        companion object {
            /**
             * Preprocess the raw input into a clean arithmetic string:
             *  - lowercases and trims
             *  - rewrites "X% of Y" -> "((X)/100)*(Y)"
             *  - rewrites a trailing "N%" (percent NOT used as modulo, i.e. not between two operands) -> "(N/100)"
             *  - maps common unicode operators (×, ÷, −) to ASCII
             */
            fun normalize(raw: String): String {
                var s = raw.lowercase()
                    .replace('\u00D7', '*') // ×
                    .replace('\u00F7', '/') // ÷
                    .replace('\u2212', '-') // −
                    .replace(",", "") // thousands separators like 1,000

                // "X% of Y" -> "((X)/100)*(Y)"
                // Handle the "of" keyword: number-or-paren-group % of  =>  percentage multiply.
                val ofRegex = Regex("""(\d+(?:\.\d+)?|\.\d+)\s*%\s*of\b""")
                s = ofRegex.replace(s) { m ->
                    "((${m.groupValues[1]})/100)*"
                }

                // Any remaining "N%" where the % directly follows a number and is NOT immediately
                // followed by another operand is treated as a literal percentage -> (N/100).
                // We rewrite the simple/common form: a number followed by '%' that is at end of
                // string or followed by an operator/closing paren/whitespace+operator.
                val percentRegex = Regex("""(\d+(?:\.\d+)?|\.\d+)\s*%(?=\s*($|[+\-*/)%]))""")
                s = percentRegex.replace(s) { m ->
                    "(${m.groupValues[1]}/100)"
                }

                return s
            }
        }
    }
}
