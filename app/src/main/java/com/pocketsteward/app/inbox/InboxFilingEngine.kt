package com.pocketsteward.app.inbox

import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.ProjectHierarchy
import com.pocketsteward.app.saved.ProjectHome
import java.util.Locale
import kotlin.math.abs

/**
 * Pure project/release inference. It never constructs absolute paths and never
 * mutates storage. The caller supplies already-authorized project-home
 * candidates, then a deterministic adapter turns the resulting semantic
 * decision into ordinary plan operations.
 */
object InboxFilingEngine {
    private const val STRONG_SCORE = 80
    private const val PROBABLE_SCORE = 55
    private const val COHORT_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val TIGHT_COHORT_WINDOW_MS = 90L * 60L * 1000L

    fun analyze(
        artifacts: List<ArtifactSignals>,
        projectHomes: List<ProjectHome>,
        projectKeywords: List<ProjectKeyword> = emptyList(),
        correctionRules: List<CorrectionRule> = emptyList(),
        storageRootPath: String,
    ): InboxFilingAnalysis {
        if (artifacts.isEmpty()) return InboxFilingAnalysis(emptyList(), emptyList())

        val homes = projectHomes
            .filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { normalize(it.path) }
            .toMutableList()

        val firstPass = artifacts.map { artifact ->
            resolveArtifact(
                artifact = artifact,
                knownHomes = homes,
                projectKeywords = projectKeywords,
                correctionRules = correctionRules,
                storageRootPath = storageRootPath,
            )
        }

        // Cohort support is deliberately second-pass. A timestamp cannot invent
        // a project; it can only attach an otherwise weak artifact to a strong
        // project/release already established by other evidence.
        val strong = firstPass.filterIsInstance<Resolved>().filter {
            it.decision.confidence == FilingConfidence.STRONG
        }
        val final = firstPass.map { result ->
            when (result) {
                is Resolved -> {
                    if (result.decision.confidence == FilingConfidence.STRONG) result
                    else strengthenByCohort(result, strong)
                }
                is Unresolved -> attachUnresolvedByCohort(result, strong)
            }
        }

        return InboxFilingAnalysis(
            decisions = final.filterIsInstance<Resolved>().map { it.decision },
            unresolved = final.filterIsInstance<Unresolved>().map {
                UnresolvedArtifact(it.artifact, it.reason)
            },
        )
    }

    private sealed interface Resolution
    private data class Resolved(val decision: FilingDecision) : Resolution
    private data class Unresolved(val artifact: ArtifactSignals, val reason: String) : Resolution
    private data class HomeStemMatch(
        val home: ProjectHome,
        val homeNorm: String,
        val overlap: Int,
        val uniqueToken: String?,
    )

    private fun resolveArtifact(
        artifact: ArtifactSignals,
        knownHomes: MutableList<ProjectHome>,
        projectKeywords: List<ProjectKeyword>,
        correctionRules: List<CorrectionRule>,
        storageRootPath: String,
    ): Resolution {
        val evidence = mutableListOf<FilingEvidence>()
        val filenameText = artifact.displayName.substringBeforeLast('.', artifact.displayName)
        val filenameNormalized = normalizeSemantic(filenameText)
        val archiveText = artifact.archiveSample.joinToString(" ")
        val searchable = listOf(
            filenameText,
            artifact.apkLabel.orEmpty(),
            artifact.apkPackageName.orEmpty(),
            archiveText,
            artifact.indexedText.take(MAX_INDEXED_TEXT),
        ).joinToString(" ")
        val searchableNormalized = normalizeSemantic(searchable)

        var explicitName: String? = null
        correctionRules.firstOrNull { filenameText.contains(it.term, ignoreCase = true) }?.let { rule ->
            explicitName = rule.destinationFolder
            evidence += FilingEvidence(
                FilingEvidenceType.USER_MAPPING,
                "Remembered correction “${rule.term}” → ${rule.destinationFolder}",
                120,
            )
        }
        if (explicitName == null) {
            projectKeywords.firstOrNull { keyword ->
                searchable.contains(keyword.term, ignoreCase = true)
            }?.let { keyword ->
                explicitName = keyword.projectFolder
                evidence += FilingEvidence(
                    FilingEvidenceType.USER_MAPPING,
                    "Project keyword “${keyword.term}” → ${keyword.projectFolder}",
                    105,
                )
            }
        }

        val scoredHomes = knownHomes.mapNotNull { home ->
            val homeEvidence = mutableListOf<FilingEvidence>()
            var score = 0

            val packageId = artifact.apkPackageName
            if (packageId != null && home.packageIds.any { it.equals(packageId, ignoreCase = true) }) {
                score += 140
                homeEvidence += FilingEvidence(
                    FilingEvidenceType.APK_PACKAGE,
                    "APK package $packageId matches this project",
                    140,
                )
            }

            val aliases = (home.aliases + home.name + home.path.substringAfterLast('/'))
                .map(::normalizeSemantic)
                .filter { it.length >= 3 }
                .distinct()
            val alias = aliases.maxByOrNull { alias ->
                when {
                    searchableNormalized.contains(alias) -> alias.length + 1000
                    alias.contains(filenameNormalized) && filenameNormalized.length >= 5 -> filenameNormalized.length + 500
                    else -> tokenOverlap(alias, searchableNormalized)
                }
            }
            if (alias != null) {
                when {
                    searchableNormalized.contains(alias) -> {
                        score += 90
                        homeEvidence += FilingEvidence(
                            FilingEvidenceType.KNOWN_PROJECT_HOME,
                            "Filename/metadata matches ${home.name}",
                            90,
                        )
                    }
                    alias.contains(filenameNormalized) && filenameNormalized.length >= 5 -> {
                        score += 82
                        homeEvidence += FilingEvidence(
                            FilingEvidenceType.EXISTING_FOLDER,
                            "Existing project folder closely matches the artifact name",
                            82,
                        )
                    }
                    tokenOverlap(alias, searchableNormalized) >= 2 -> {
                        score += 60
                        homeEvidence += FilingEvidence(
                            FilingEvidenceType.EXISTING_FOLDER,
                            "Existing project folder shares strong name terms",
                            60,
                        )
                    }
                }
            }

            if (explicitName != null && (
                    home.name.equals(explicitName, ignoreCase = true) ||
                        home.path.substringAfterLast('/').equals(explicitName, ignoreCase = true)
                    )
            ) {
                score += 150
                homeEvidence += FilingEvidence(
                    FilingEvidenceType.USER_MAPPING,
                    "Explicit mapping targets ${home.name}",
                    150,
                )
            }

            artifact.apkLabel?.takeIf { it.isNotBlank() }?.let { label ->
                val labelNorm = normalizeSemantic(label)
                val homeNorm = normalizeSemantic(home.name)
                if (labelNorm.length >= 4 && (
                        homeNorm.contains(labelNorm) || labelNorm.contains(homeNorm)
                    )
                ) {
                    score += 100
                    homeEvidence += FilingEvidence(
                        FilingEvidenceType.APK_LABEL,
                        "APK label “$label” matches ${home.name}",
                        100,
                    )
                }
            }

            if (score > 0) Triple(home, score, homeEvidence) else null
        }.sortedByDescending { it.second }

        var selectedHome: ProjectHome? = scoredHomes.firstOrNull()?.first
        var selectedScore = scoredHomes.firstOrNull()?.second ?: 0
        if (selectedHome != null) evidence += scoredHomes.first().third

        if (selectedHome == null && explicitName != null) {
            selectedHome = newHome(explicitName!!, storageRootPath)
            selectedScore = 105
            evidence += FilingEvidence(
                FilingEvidenceType.USER_MAPPING,
                "No existing home matched; propose $explicitName",
                105,
            )
        }

        if (selectedHome == null) {
            artifact.apkLabel?.takeIf { plausibleProjectName(it) }?.let { label ->
                selectedHome = newHome(label, storageRootPath)
                selectedScore = 95
                evidence += FilingEvidence(
                    FilingEvidenceType.APK_LABEL,
                    "APK identifies the app as “$label”",
                    95,
                )
            }
        }

        val inferredRelease = inferRelease(artifact)
        val inferredStem = inferProjectStem(filenameText)
        if (selectedHome == null && inferredStem != null) {
            // Prefer an existing home even when the raw normalized strings are
            // not exact. This is what maps LilithCompanion-* to an existing
            // “Lilith Companion App” folder rather than inventing a sibling.
            val stemNorm = normalizeSemantic(inferredStem)
            val stemTokens = tokenized(inferredStem)
            val discovered = knownHomes
                .map { home ->
                    val homeNorm = normalizeSemantic(home.name)
                    val homeTokens = tokenized(home.name)
                    val overlap = tokenOverlap(stemTokens, homeTokens)
                    val uniqueToken = stemTokens
                        .intersect(homeTokens.toSet())
                        .firstOrNull { token ->
                            token.length >= 5 && knownHomes.count { candidate ->
                                token in tokenized(candidate.name) ||
                                    candidate.aliases.any { token in tokenized(it) }
                            } == 1
                        }
                    HomeStemMatch(home, homeNorm, overlap, uniqueToken)
                }
                .filter { match ->
                    match.homeNorm.contains(stemNorm) ||
                        stemNorm.contains(match.homeNorm) ||
                        match.overlap >= 2 ||
                        match.uniqueToken != null
                }
                .maxByOrNull { match ->
                    (if (match.homeNorm.contains(stemNorm) || stemNorm.contains(match.homeNorm)) 100 else 0) +
                        (match.overlap * 10) +
                        (if (match.uniqueToken != null) 5 else 0)
                }
            if (discovered != null) {
                selectedHome = discovered.home
                selectedScore = if (
                    discovered.homeNorm.contains(stemNorm) ||
                    stemNorm.contains(discovered.homeNorm) ||
                    discovered.overlap >= 2
                ) 86 else 65
                evidence += FilingEvidence(
                    FilingEvidenceType.EXISTING_FOLDER,
                    if (discovered.uniqueToken != null && discovered.overlap < 2) {
                        "Artifact shares distinctive project term “${discovered.uniqueToken}” with ${discovered.home.name}"
                    } else {
                        "Artifact name matches existing project folder ${discovered.home.name}"
                    },
                    selectedScore,
                )
            } else if (projectStemStrength(inferredStem) >= 2 && inferredRelease != null) {
                // A filename alone may propose a new project home only when it
                // also carries a release identity. This prevents arbitrary
                // descriptive downloads from becoming one-off top-level folders.
                selectedHome = newHome(inferredStem, storageRootPath)
                selectedScore = 62
                evidence += FilingEvidence(
                    FilingEvidenceType.FILENAME,
                    "Filename suggests versioned project “$inferredStem”",
                    62,
                )
            }
        }

        if (selectedHome == null) {
            return Unresolved(artifact, "No project identity was strong enough to choose a durable home.")
        }

        val release = inferredRelease
        if (artifact.apkVersionName?.isNotBlank() == true) {
            evidence += FilingEvidence(
                FilingEvidenceType.APK_VERSION,
                "APK version ${artifact.apkVersionName}",
                35,
            )
        } else if (release != null) {
            evidence += FilingEvidence(
                FilingEvidenceType.VERSION_TOKEN,
                "Version/release token $release",
                20,
            )
        }

        artifact.archiveSample.firstOrNull { entry ->
            val homeNorm = normalizeSemantic(selectedHome!!.name)
            normalizeSemantic(entry).contains(homeNorm.take(MIN_ARCHIVE_MATCH_CHARS)) ||
                (release != null && entry.contains(release, ignoreCase = true))
        }?.let { entry ->
            selectedScore += 15
            evidence += FilingEvidence(
                FilingEvidenceType.ARCHIVE_ENTRY,
                "ZIP contents support the match (${entry.take(80)})",
                15,
            )
        }

        if (artifact.indexedText.isNotBlank()) {
            val homeTokens = tokenized(selectedHome!!.name).filter { it.length >= 4 }
            if (homeTokens.any { artifact.indexedText.contains(it, ignoreCase = true) }) {
                selectedScore += 18
                evidence += FilingEvidence(
                    FilingEvidenceType.INDEXED_CONTENT,
                    "Indexed document content names the project",
                    18,
                )
            }
        }

        val selectedHomeName = normalizeSemantic(selectedHome!!.name)
        if (selectedHomeName.length >= 4 && filenameNormalized.contains(selectedHomeName)) {
            selectedScore += 12
            evidence += FilingEvidence(
                FilingEvidenceType.FILENAME,
                "Filename contains the project identity",
                12,
            )
        }

        val confidence = when {
            selectedScore >= STRONG_SCORE -> FilingConfidence.STRONG
            selectedScore >= PROBABLE_SCORE -> FilingConfidence.PROBABLE
            else -> FilingConfidence.UNRESOLVED
        }
        if (confidence == FilingConfidence.UNRESOLVED) {
            return Unresolved(artifact, "Project evidence was too weak to file automatically.")
        }

        return Resolved(
            FilingDecision(
                artifact = artifact,
                projectHome = selectedHome!!,
                release = release,
                confidence = confidence,
                evidence = evidence.distinctBy { it.type to it.detail },
            ),
        )
    }

    private fun strengthenByCohort(result: Resolved, strong: List<Resolved>): Resolution {
        val decision = result.decision
        if (decision.confidence != FilingConfidence.PROBABLE) return result
        val modified = decision.artifact.modifiedAt ?: return result
        val compatible = strong.map { it.decision }.filter { candidate ->
            candidate.projectHome.path.equals(decision.projectHome.path, ignoreCase = true) &&
                releasesCompatible(candidate.release, decision.release) &&
                candidate.artifact.modifiedAt?.let { abs(it - modified) <= COHORT_WINDOW_MS } == true
        }
        if (compatible.isEmpty()) return result
        return Resolved(
            decision.copy(
                confidence = FilingConfidence.STRONG,
                evidence = decision.evidence + FilingEvidence(
                    FilingEvidenceType.COHORT_TIME,
                    "Matches a strong ${decision.projectHome.name} download cohort",
                    25,
                ),
            ),
        )
    }

    private fun attachUnresolvedByCohort(result: Unresolved, strong: List<Resolved>): Resolution {
        val modified = result.artifact.modifiedAt ?: return result
        val release = inferRelease(result.artifact)
        val candidates = strong.map { it.decision }.filter { candidate ->
            candidate.artifact.modifiedAt?.let { abs(it - modified) <= TIGHT_COHORT_WINDOW_MS } == true &&
                releasesCompatible(candidate.release, release)
        }
        val distinctHomes = candidates.distinctBy { normalize(it.projectHome.path) }
        if (distinctHomes.size != 1) return result
        val anchor = distinctHomes.single()
        val evidence = mutableListOf(
            FilingEvidence(
                FilingEvidenceType.COHORT_TIME,
                "Downloaded near a strong ${anchor.projectHome.name} cohort",
                45,
            ),
        )
        if (release != null) {
            evidence += FilingEvidence(FilingEvidenceType.VERSION_TOKEN, "Shares release token $release", 25)
        }
        val hasProjectHint = tokenized(result.artifact.displayName)
            .intersect(tokenized(anchor.projectHome.name).toSet())
            .any { it.length >= 4 }
        if (!hasProjectHint && release == null) return result
        return Resolved(
            FilingDecision(
                artifact = result.artifact,
                projectHome = anchor.projectHome,
                release = release ?: anchor.release,
                confidence = FilingConfidence.PROBABLE,
                evidence = evidence,
            ),
        )
    }

    fun inferRelease(artifact: ArtifactSignals): String? {
        artifact.apkVersionName
            ?.trim()
            ?.takeIf { VERSION_REGEX.matchesPrefixish(it) }
            ?.let { raw -> VERSION_REGEX.find(raw)?.value?.normalizeVersionToken() }
            ?.let { return it }

        VERSION_REGEX.find(artifact.displayName)?.value?.normalizeVersionToken()?.let { return it }
        artifact.archiveSample.asSequence()
            .mapNotNull { VERSION_REGEX.find(it)?.value?.normalizeVersionToken() }
            .firstOrNull()
            ?.let { return it }
        return null
    }

    fun inferProjectStem(displayName: String): String? {
        val base = displayName.substringBeforeLast('.', displayName)
        val camelSplit = base.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        val withoutVersions = VERSION_REGEX.replace(camelSplit, " ")
        val tokens = withoutVersions
            .split(Regex("[^A-Za-z0-9]+"))
            .map { it.trim() }
            .filter { token ->
                token.length >= 2 &&
                    token.lowercase(Locale.US) !in GENERIC_TOKENS &&
                    token.any(Char::isLetter)
            }
            .take(5)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            token.lowercase(Locale.US).replaceFirstChar(Char::titlecase)
        }.take(80)
    }

    private fun projectStemStrength(value: String): Int = tokenized(value).count {
        it.length >= 3 && it !in GENERIC_TOKENS
    }

    private fun plausibleProjectName(value: String): Boolean {
        val cleaned = value.trim()
        if (cleaned.length !in 2..80) return false
        val norm = normalizeSemantic(cleaned)
        return norm !in GENERIC_PROJECT_NAMES && norm.any(Char::isLetter)
    }

    private fun newHome(name: String, storageRootPath: String): ProjectHome {
        val safeName = name.trim().replace(Regex("[\\/\\p{Cntrl}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        return ProjectHome(
            name = safeName,
            path = "${storageRootPath.trimEnd('/')}/$safeName",
            aliases = listOf(safeName),
            hierarchy = ProjectHierarchy.VERSIONED,
        )
    }

    private fun releasesCompatible(a: String?, b: String?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.equals(b, ignoreCase = true)
    }

    private fun tokenOverlap(a: String, b: String): Int = tokenOverlap(tokenized(a), tokenized(b))
    private fun tokenOverlap(a: List<String>, b: List<String>): Int = a.toSet().intersect(b.toSet()).size

    private fun normalize(value: String): String = value.trim().trimEnd('/').lowercase(Locale.US)

    private fun normalizeSemantic(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")

    private fun tokenized(value: String): List<String> = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.US)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in GENERIC_TOKENS }

    private fun String.normalizeVersionToken(): String =
        trim().removePrefix("v").removePrefix("V").replace('_', '-')

    private fun Regex.matchesPrefixish(value: String): Boolean = find(value) != null

    private const val MAX_INDEXED_TEXT = 8_000
    private const val MIN_ARCHIVE_MATCH_CHARS = 5

    private val VERSION_REGEX = Regex(
        "(?i)(?<![A-Za-z0-9])v?(\\d+\\.\\d+(?:\\.\\d+)?(?:[-_.]?(?:dev|alpha|beta|rc|hb)\\d*)?)(?![A-Za-z0-9])",
    )

    private val GENERIC_TOKENS = setOf(
        "arm64", "armeabi", "x86", "x64", "canonical", "signed", "release", "debug",
        "source", "src", "build", "bundle", "handoff", "private", "public", "current",
        "final", "new", "copy", "notes", "note", "readme", "apk", "zip", "app", "android",
        "image", "images", "screenshot", "screenshots", "file", "files", "export", "backup",
    )

    private val GENERIC_PROJECT_NAMES = setOf(
        "app", "application", "android", "download", "downloads", "release", "build", "source",
    )
}
