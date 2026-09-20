package com.pocketsteward.app.ai

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

@Generable
data class CoherenceAuditOutput(
    @Guide(description = "One result for each input document, preserving the exact input id.")
    val findings: List<CoherenceFindingOutput>,
)

@Generable
data class CoherenceFindingOutput(
    @Guide(description = "Exact id from the input document.")
    val id: String,
    @Guide(description = "Exactly one of BELONGS, QUESTIONABLE, DOES_NOT_BELONG, UNCERTAIN.")
    val classification: String,
    @Guide(description = "One concise sentence explaining the classification.")
    val reason: String,
    @Guide(description = "A short suggested folder or group name. Use an empty string when no regrouping is suggested.")
    val suggestedGroup: String,
)
