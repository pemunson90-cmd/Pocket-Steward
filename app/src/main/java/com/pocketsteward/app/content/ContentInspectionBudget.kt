package com.pocketsteward.app.content

/** Bulk filing reads bounded evidence; an explicit full index remains a separate operation. */
data class ContentInspectionBudget(
    val profile: String,
    val maxTextBytes: Int,
    val maxTextChars: Int,
    val maxPdfPages: Int,
    val maxOcrPages: Int,
    val maxDecodedArchiveBytes: Int,
    val maxArchiveEntries: Int,
) {
    companion object {
        val FULL = ContentInspectionBudget("FULL", 1_048_576, 1_000_000, 250, 250, 32 * 1024 * 1024, 2_000)
        val FILING = ContentInspectionBudget("FILING", 32 * 1024, 12_000, 8, 2, 4 * 1024 * 1024, 200)
    }
}
