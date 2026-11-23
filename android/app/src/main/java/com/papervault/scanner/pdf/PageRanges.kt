package com.papervault.scanner.pdf

/** Parses human page ranges ("1-3, 5, 8-") into 0-based index groups. */
object PageRanges {

    /** Each comma-separated part becomes one group. Throws [IllegalArgumentException] on bad input. */
    fun parseGroups(text: String, pageCount: Int): List<List<Int>> {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Enter at least one page range" }
        return parts.map { part ->
            val dash = part.indexOf('-')
            val (start, end) = if (dash < 0) {
                val n = part.toIntOrNull() ?: throw IllegalArgumentException("Invalid page \"$part\"")
                n to n
            } else {
                val a = part.substring(0, dash).trim().ifEmpty { "1" }.toIntOrNull()
                val b = part.substring(dash + 1).trim().ifEmpty { "$pageCount" }.toIntOrNull()
                if (a == null || b == null) throw IllegalArgumentException("Invalid range \"$part\"")
                a to b
            }
            require(start in 1..pageCount && end in 1..pageCount) { "Pages must be between 1 and $pageCount" }
            require(start <= end) { "Range \"$part\" is backwards" }
            (start - 1 until end).toList()
        }
    }

    /** Flatten groups into one ordered, de-duplicated list. */
    fun parseFlat(text: String, pageCount: Int): List<Int> = parseGroups(text, pageCount).flatten().distinct()

    fun everyPage(pageCount: Int): List<List<Int>> = (0 until pageCount).map { listOf(it) }

    fun chunks(pageCount: Int, size: Int): List<List<Int>> {
        require(size >= 1) { "Chunk size must be at least 1" }
        return (0 until pageCount).chunked(size)
    }
}
