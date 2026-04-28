package com.example.team_arthsetu.bankstatement

/**
 * Normalises PDFBox output for stable line-based parsing (multi-line transactions, column gaps).
 */
object PdfTextNormalizer {

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("""[ \t\u00A0]+"""), " ")
        t = t.replace(Regex("""\n{3,}"""), "\n\n")
        return t.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
