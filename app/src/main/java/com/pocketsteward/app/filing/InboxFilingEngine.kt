package com.pocketsteward.app.filing

import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic project/release resolver for inbox filing.
 *
 * The engine never emits an arbitrary absolute path from model text. It only
 * chooses among already-authorized homes or derives a single safe project
 * name under [storageRoot]. Path construction and mutation stay in the plan
 * adapter / validator layer.
 */
object InboxFilingEngine {
    private const val STRONG_SCORE = 82
    private const val PROBABLE_SCORE = 62
    private const val COHORT_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val SUPPORTING_COHORT_WINDOW_MS = 45L * 60L * 1000L

    private val genericTokens = setOf(
        "app", "application", "apk", "source", "src", "build", "release", "debug",
        "final", "copy", "handoff", "notes", "note", "readme", "master", "private",
        "signed", "canonical", "arm64", "universal", "bundle", "zip", "file", "files",
        "image", "img", "screenshot", "android", "version", "ver", "dev",
    )

    fun resolve(
        artifacts: List<FilingArtifact>,
        persistedHomes: List<ProjectHomeCandidate>,
        discoveredHomes: List<ProjectHomeCandidate>,
        projectKeywords: List<ProjectKeyword>,
        correctionRules: List<CorrectionRule>,
        storageRoot: String,
    ): InboxFilingResult {
        if (artifacts.isEmpty()) return InboxFilingResult(emptyList())

        val homes = (persistedHomes + discoveredHomes)
            .distinctBy { normalize(it.path) }
        val repeatedLabels = artifacts
            .mapNotNull { guessedProjectLabel(it.displayName) }
            .groupingBy { normalizeCompact(it) }
            .eachCount()

        val anchors = artifacts.map { artifact ->
            resolveAnchor(
                artifact = artifact,
                homes = homes,
                projectKeywords = projectKeywords,
                correctionRules = correctionRules,
                storageRoot = storageRoot,
                repeatedLabels = repeatedLabels,
            )
        }

        // Second pass: a file with real project evidence but no release can
        // inherit a nearby unique release from that same project. Time alone
        // is never enough to establish project membership.
        val releaseResolved = anchors.map { decision ->
            if (decision.confidence == FilingConfidence.UNRESOLVED || decision.release != null || decision.projectName == null) {
                decision
            } else {
                val candidates = anchors.filter { other ->
                    other !== decision &&
                        other.projectName != null &&
                        normalizeCompact(other.projectName) == normalizeCompact(decision.projectName) &&
                        other.release != null &&
                        closeInTime(eventTime(decision.artifact), eventTime(other.artifact), COHORT_WINDOW_MS)
                }
                val releases = candidates.mapNotNull { it.release }.distinct()
                if (releases.size == 1 && decision.evidence.any { it.kind != FilingEvidenceKind.COHORT }) {
                    val release = releases.single()
                    decision.copy(
                        release = release,
                        destinationDirectory = destinationFor(
                            requireNotNull(decision.projectHome),
                            release,
                        ),
                        confidence = if (decision.confidence == FilingConfidence.STRONG) FilingConfidence.STRONG else FilingConfidence.PROBABLE,
                        evidence = decision.evidence + FilingEvidence(
                            FilingEvidenceKind.COHORT,
                            "nearby artifact from the same project identifies release $release",
                            18,
                        ),
                    )
                } else {
                    decision
                }
            }
        }

        // A generic supporting asset may still be useful to show in review when
        // it arrived in the middle of one unambiguous, strongly identified
        // project/release burst. This never becomes a default-selected strong
        // match: temporal proximity alone only promotes it to PROBABLE.
        val resolved = releaseResolved.map { decision ->
            if (decision.confidence != FilingConfidence.UNRESOLVED || !isSupportingArtifact(decision.artifact)) {
                decision
            } else {
                val nearby = releaseResolved.filter { other ->
                    other.confidence == FilingConfidence.STRONG &&
                        other.projectHome != null &&
                        other.release != null &&
                        closeInTime(eventTime(decision.artifact), eventTime(other.artifact), SUPPORTING_COHORT_WINDOW_MS)
                }
                val cohorts = nearby
                    .groupBy { normalize(requireNotNull(it.projectHome).path) to requireNotNull(it.release) }
                    .entries
                    .sortedByDescending { it.value.size }
                val winner = cohorts.firstOrNull()?.value.orEmpty()
                val runnerUpSize = cohorts.getOrNull(1)?.value?.size ?: 0
                if (winner.isNotEmpty() && winner.size > runnerUpSize) {
                    val anchor = winner.first()
                    val home = requireNotNull(anchor.projectHome)
                    val release = requireNotNull(anchor.release)
                    decision.copy(
                        projectName = home.name,
                        projectHome = home,
                        release = release,
                        destinationDirectory = destinationFor(home, release),
                        confidence = FilingConfidence.PROBABLE,
                        evidence = listOf(
                            FilingEvidence(
                                FilingEvidenceKind.COHORT,
                                "possible supporting file: arrived with strong ${home.name} $release artifacts",
                                45,
                            ),
                        ),
                    )
                } else {
                    decision
                }
            }
        }

        return InboxFilingResult(resolved)
    }

    private fun resolveAnchor(
        artifact: FilingArtifact,
        homes: List<ProjectHomeCandidate>,
        projectKeywords: List<ProjectKeyword>,
        correctionRules: List<CorrectionRule>,
        storageRoot: String,
        repeatedLabels: Map<String, Int>,
    ): FilingDecision {
        val evidenceByHome = linkedMapOf<ProjectHomeCandidate, MutableList<FilingEvidence>>()
        fun add(home: ProjectHomeCandidate, kind: FilingEvidenceKind, detail: String, weight: Int) {
            evidenceByHome.getOrPut(home) { mutableListOf() } += FilingEvidence(kind, detail, weight)
        }

        correctionRules.forEach { rule ->
            if (artifact.displayName.contains(rule.term, ignoreCase = true)) {
                val home = homes.bestNamed(rule.destinationFolder)
                    ?: syntheticHome(rule.destinationFolder, storageRoot)
                add(home, FilingEvidenceKind.USER_MAPPING, "learned mapping “${rule.term}” → ${rule.destinationFolder}", 120)
            }
        }

        projectKeywords.forEach { keyword ->
            val filenameHit = artifact.displayName.contains(keyword.term, ignoreCase = true)
            val contentHit = !filenameHit && artifact.indexedText.contains(keyword.term, ignoreCase = true)
            val archiveHit = !filenameHit && artifact.archiveSample.any { it.contains(keyword.term, ignoreCase = true) }
            if (filenameHit || contentHit || archiveHit) {
                val home = homes.bestNamed(keyword.projectFolder)
                    ?: syntheticHome(keyword.projectFolder, storageRoot)
                val weight = when {
                    filenameHit -> 105
                    archiveHit -> 82
                    else -> 74
                }
                val kind = when {
                    filenameHit -> FilingEvidenceKind.FILENAME
                    archiveHit -> FilingEvidenceKind.ARCHIVE_ENTRY
                    else -> FilingEvidenceKind.INDEXED_CONTENT
                }
                add(home, kind, "project mapping “${keyword.term}” → ${keyword.projectFolder}", weight)
            }
        }

        homes.forEach { home ->
            val inferredAlias = meaningfulTokens(home.name).joinToString(" ").takeIf { it.length >= 4 }
            val labels = (listOfNotNull(home.name, inferredAlias) + home.aliases)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val filenameMatch = labels.maxOfOrNull { label -> textMatchScore(artifact.displayName, label) } ?: 0
            if (filenameMatch >= 65) {
                add(home, FilingEvidenceKind.PROJECT_HOME, "filename matches project home ${home.name}", filenameMatch + if (home.persisted) 10 else 0)
            }
            val labelMatch = artifact.apkLabel?.let { label -> textMatchScore(label, home.name) } ?: 0
            if (labelMatch >= 65) {
                add(home, FilingEvidenceKind.APK_LABEL, "APK label matches ${home.name}", labelMatch + 15)
            }
            val packageName = artifact.apkPackageName
            if (packageName != null && home.packageIds.any { it.equals(packageName, ignoreCase = true) }) {
                add(home, FilingEvidenceKind.APK_PACKAGE, "APK package $packageName is registered to ${home.name}", 140)
            }
            if (labels.any { label -> artifact.archiveSample.any { entry -> entry.contains(label, ignoreCase = true) } }) {
                add(home, FilingEvidenceKind.ARCHIVE_ENTRY, "archive entries mention ${home.name}", 78)
            }
            if (artifact.indexedText.isNotBlank() && labels.any { artifact.indexedText.contains(it, ignoreCase = true) }) {
                add(home, FilingEvidenceKind.INDEXED_CONTENT, "indexed document content mentions ${home.name}", 70)
            }
        }

        artifact.apkLabel?.takeIf { isUsefulLabel(it) }?.let { label ->
            val home = homes.bestNamed(label) ?: syntheticHome(label, storageRoot)
            add(home, FilingEvidenceKind.APK_LABEL, "APK metadata identifies “$label”", 112)
        }

        artifact.apkPackageName?.let { pkg ->
            val packageLeaf = pkg.substringAfterLast('.').takeIf { it.length >= 3 }
            if (packageLeaf != null) {
                homes.forEach { home ->
                    val score = textMatchScore(packageLeaf, home.name)
                    if (score >= 70) add(home, FilingEvidenceKind.APK_PACKAGE, "APK package name resembles ${home.name}", score)
                }
            }
        }

        guessedProjectLabel(artifact.displayName)?.let { guessed ->
            val repeated = (repeatedLabels[normalizeCompact(guessed)] ?: 0) >= 2
            val matchingHome = homes.bestNamed(guessed)
            if (matchingHome != null && textMatchScore(guessed, matchingHome.name) >= 65) {
                add(matchingHome, FilingEvidenceKind.FILENAME, "filename identifies ${matchingHome.name}", if (repeated) 92 else 78)
            } else if (repeated) {
                val synthetic = syntheticHome(guessed, storageRoot)
                add(synthetic, FilingEvidenceKind.FILENAME, "repeated filename family identifies $guessed", 84)
            }
        }

        val best = evidenceByHome.entries.maxByOrNull { (_, evidence) -> evidence.sumOf { it.weight } }
        if (best == null) {
            return FilingDecision(
                artifact = artifact,
                projectName = null,
                projectHome = null,
                release = releaseOf(artifact),
                destinationDirectory = null,
                confidence = FilingConfidence.UNRESOLVED,
                evidence = emptyList(),
            )
        }

        val home = best.key
        val evidence = best.value.distinctBy { it.kind to it.detail }
        val score = evidence.sumOf { it.weight }
        val release = releaseOf(artifact)
        val confidence = when {
            score >= STRONG_SCORE && (release != null || home.hierarchy == ProjectHierarchyStrategy.FLAT) -> FilingConfidence.STRONG
            score >= STRONG_SCORE -> FilingConfidence.PROBABLE
            score >= PROBABLE_SCORE -> FilingConfidence.PROBABLE
            else -> FilingConfidence.UNRESOLVED
        }

        return FilingDecision(
            artifact = artifact,
            projectName = home.name,
            projectHome = home,
            release = release,
            destinationDirectory = if (confidence == FilingConfidence.UNRESOLVED) null else destinationFor(home, release),
            confidence = confidence,
            evidence = evidence,
            createsProjectHome = !home.persisted && homes.none { normalize(it.path) == normalize(home.path) },
        )
    }

    fun releaseOf(artifact: FilingArtifact): String? {
        artifact.apkVersionName?.trim()?.takeIf { isUsefulVersion(it) }?.let { return normalizeVersion(it) }
        VERSION_REGEX.find(artifact.displayName)?.value?.let { return normalizeVersion(it) }
        artifact.archiveSample.asSequence()
            .mapNotNull { VERSION_REGEX.find(it)?.value }
            .firstOrNull()
            ?.let { return normalizeVersion(it) }
        return null
    }

    fun sanitizeSegment(value: String?): String? {
        val clean = value?.trim()?.trimEnd('.', ' ')?.take(80)?.trim() ?: return null
        if (clean.isBlank() || clean == "." || clean == "..") return null
        if ('/' in clean || '\\' in clean || clean.any { it.isISOControl() }) return null
        return clean
    }

    private fun destinationFor(home: ProjectHomeCandidate, release: String?): String {
        val base = home.path.trimEnd('/')
        return when (home.hierarchy) {
            ProjectHierarchyStrategy.FLAT -> base
            ProjectHierarchyStrategy.VERSIONED -> sanitizeSegment(release)?.let { "$base/$it" } ?: base
        }
    }

    private fun syntheticHome(name: String, storageRoot: String): ProjectHomeCandidate {
        val safe = sanitizeSegment(name) ?: "Unsorted project"
        return ProjectHomeCandidate(
            name = safe,
            path = storageRoot.trimEnd('/') + "/" + safe,
            aliases = listOf(safe),
            persisted = false,
        )
    }

    private fun List<ProjectHomeCandidate>.bestNamed(label: String): ProjectHomeCandidate? =
        map { it to textMatchScore(label, it.name) }
            .filter { it.second >= 62 }
            .maxWithOrNull(compareBy<Pair<ProjectHomeCandidate, Int>> { it.second }.thenBy { if (it.first.persisted) 1 else 0 })
            ?.first

    private fun eventTime(artifact: FilingArtifact): Long? = artifact.createdAt ?: artifact.modifiedAt

    private fun closeInTime(a: Long?, b: Long?, windowMs: Long): Boolean =
        a != null && b != null && abs(a - b) <= windowMs

    private fun isSupportingArtifact(artifact: FilingArtifact): Boolean =
        artifact.extension.lowercase(Locale.ROOT) in SUPPORTING_EXTENSIONS &&
            guessedProjectLabel(artifact.displayName) == null

    private fun textMatchScore(a: String, b: String): Int {
        val ac = normalizeCompact(a)
        val bc = normalizeCompact(b)
        if (ac.isBlank() || bc.isBlank()) return 0
        if (ac == bc) return 100
        if (ac.contains(bc) || bc.contains(ac)) {
            val shorter = minOf(ac.length, bc.length)
            val longer = maxOf(ac.length, bc.length)
            return (72 + (28.0 * shorter / longer)).toInt().coerceAtMost(98)
        }
        val at = meaningfulTokens(a)
        val bt = meaningfulTokens(b)
        if (at.isEmpty() || bt.isEmpty()) return 0
        val intersection = at.intersect(bt).size
        if (intersection == 0) return 0
        val union = at.union(bt).size
        return (55 + 45.0 * intersection / union).toInt()
    }

    private fun guessedProjectLabel(filename: String): String? {
        val base = filename.substringBeforeLast('.', filename)
            .replace(VERSION_REGEX, " ")
            .replace(Regex("(?i)\\b(?:source|src|build|release|debug|signed|canonical|handoff|notes?|master|private|arm64|universal|apk|zip|bundle|dev\\d*)\\b"), " ")
            .replace(Regex("[_\\-.]+"), " ")
            .trim()
        val tokens = base.split(Regex("\\s+"))
            .filter { token ->
                token.length >= 2 &&
                    token.lowercase(Locale.ROOT) !in genericTokens &&
                    token.any(Char::isLetter)
            }
            .take(4)
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString(" ").trim()
        if (joined.length < 3) return null
        return joined.split(Regex("(?<=[a-z])(?=[A-Z])|\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    private fun meaningfulTokens(value: String): Set<String> =
        value.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in genericTokens && !it.all(Char::isDigit) }
            .toSet()

    private fun normalize(value: String): String = value.trim().trimEnd('/').lowercase(Locale.ROOT)
    private fun normalizeCompact(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun isUsefulLabel(value: String): Boolean = value.trim().length >= 3 && value.lowercase(Locale.ROOT) !in genericTokens
    private fun isUsefulVersion(value: String): Boolean = value.any(Char::isDigit) && value.length <= 40
    private fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V").trim(' ', '-', '_')

    private val SUPPORTING_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "svg", "txt", "md", "json", "yaml", "yml",
    )

    private val VERSION_REGEX = Regex(
        "(?i)(?<![A-Za-z0-9])v?\\d+(?:\\.\\d+){1,3}(?:[-_](?:dev|alpha|beta|rc|hb)[A-Za-z0-9.-]*)?(?![A-Za-z0-9])",
    )
}