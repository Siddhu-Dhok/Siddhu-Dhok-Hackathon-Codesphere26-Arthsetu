package com.example.team_arthsetu.bankstatement

/**
 * Normalises common OCR confusions before regex parsing.
 */
object OcrTextCleaner {

    private val noiseChars = Regex("""[^\w\s₹.,:/\\-]|[_*•·]""")

    fun cleanLine(line: String): String {
        var s = line.trim()
        if (s.isEmpty()) return ""
        s = noiseChars.replace(s, " ")
        // Common OCR separators/noise near dates and UPI refs
        s = s.replace('|', '/')
        s = s.replace('¦', '/')
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = fixNumericContext(s)
        return s.trim()
    }

    fun cleanFullText(text: String): String =
        text.lines().map { cleanLine(it) }.filter { it.isNotBlank() }.joinToString("\n")

    /**
     * In sequences that look like amounts or dates, fix OCR confusions:
     * O→0, l/I→1, S→5, B→8, Z→2, G→6 (only in numeric context).
     */
    private fun fixNumericContext(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == 'O' || c == 'o' -> {
                    if ((i > 0 && s[i - 1].isDigit()) || (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('0')
                    } else {
                        sb.append(c)
                    }
                }
                c == 'l' || c == 'I' -> {
                    if ((i > 0 && s[i - 1].isDigit()) || (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('1')
                    } else {
                        sb.append(c)
                    }
                }
                c == 'S' || c == 's' -> {
                    if (i > 0 && s[i - 1].isDigit() && (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('5')
                    } else {
                        sb.append(c)
                    }
                }
                c == 'B' -> {
                    if ((i > 0 && s[i - 1].isDigit()) || (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('8')
                    } else {
                        sb.append(c)
                    }
                }
                c == 'Z' || c == 'z' -> {
                    if ((i > 0 && s[i - 1].isDigit()) || (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('2')
                    } else {
                        sb.append(c)
                    }
                }
                c == 'G' -> {
                    if ((i > 0 && s[i - 1].isDigit()) || (i + 1 < s.length && s[i + 1].isDigit())) {
                        sb.append('6')
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
