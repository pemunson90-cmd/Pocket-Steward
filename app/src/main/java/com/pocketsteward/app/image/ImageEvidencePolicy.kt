package com.pocketsteward.app.image

import java.util.Locale

/** Describes observed evidence without inventing a scene, identity or project. */
object ImageEvidencePolicy {
    const val MAX_TEXT_CHARS = 4_000
    private val interfaceTerms = Regex("(?i)\\b(settings|notifications?|wi[ -]?fi|battery|search|cancel|back|home)\\b")
    private val clock = Regex("\\b[0-2]?\\d:[0-5]\\d\\b")
    private val percentage = Regex("\\b\\d{1,3}%")

    fun boundedText(text: String): String = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
        .trim().take(MAX_TEXT_CHARS)

    fun screenshotEvidence(name: String, width: Int, height: Int, text: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        if (listOf("screenshot", "screen_shot", "screen-shot", "screen capture", "screencapture").any { it in lower })
            return "Filename suggests a screenshot."
        if (width <= 0 || height <= 0) return null
        val aspect = height.toDouble() / width
        val terms = interfaceTerms.findAll(text.take(MAX_TEXT_CHARS)).map { it.value.lowercase(Locale.ROOT) }.toSet()
        if (aspect in 1.2..3.0 && terms.size >= 3 && (clock.containsMatchIn(text) || percentage.containsMatchIn(text)))
            return "Portrait screen proportions and detected interface text suggest a screenshot."
        return null
    }

    fun description(width: Int, height: Int, labels: List<ImageLabelScore>, screenshot: String?, text: String): String = buildString {
        if (width > 0 && height > 0) {
            append(when { width == height -> "Square"; height > width -> "Portrait"; else -> "Landscape" })
            append(" image ($width × $height).")
        }
        if (screenshot != null) append(" $screenshot")
        val usable = labels.filter { it.confidence.isFinite() && it.confidence in ImageUnderstanding.MIN_CONFIDENCE..1f }
            .take(ImageUnderstanding.MAX_LABELS)
        if (usable.isNotEmpty()) append(" Local model labels: ${usable.joinToString { it.label }}.")
        val excerpt = boundedText(text).replace(Regex("\\s+"), " ").take(240)
        if (excerpt.isNotBlank()) append(" Detected text: “$excerpt”.")
        if (isEmpty()) append("No usable visual or text evidence was detected.")
    }.trim()
}
