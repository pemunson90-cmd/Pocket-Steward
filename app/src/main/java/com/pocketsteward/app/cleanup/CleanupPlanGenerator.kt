package com.pocketsteward.app.cleanup

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.rules.RuleEngine
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.parseFileRef

/**
 * Plan Section 4/9: the rule engine does the planning, no model involved.
 * One [PlannedOperation.CreateDirectory] per destination folder actually
 * needed, one [PlannedOperation.Move] per file [RuleEngine] classified with
 * full confidence. A file the rule engine couldn't place with confidence
 * 1.0 is simply never turned into an operation — Decision 5's "leave this
 * alone" applies at generation time, the same way it applies in
 * [com.pocketsteward.app.plan.PlanValidator] for a different reason.
 *
 * Deliberately does not try to detect "this file is already in the right
 * folder" itself: [com.pocketsteward.app.plan.PlanValidator] already
 * rejects a move onto an occupied destination with a real reason, and
 * Milestone 2's own on-device round confirmed that's exactly what happens
 * (files already inside `Downloads/APKs` showed up as "left untouched,
 * already exists" rather than errors) — duplicating that check here would
 * just be the same rule enforced twice.
 */
object CleanupPlanGenerator {
    fun generate(
        scopeRoot: FileRef,
        records: List<FileRecord>,
        projectKeywords: List<ProjectKeyword> = emptyList(),
    ): AgentPlan {
        require(scopeRoot is FileRef.Direct) { "Smart cleanup currently only supports a Direct-mode scope root, got $scopeRoot" }

        val operations = mutableListOf<PlannedOperation>()
        val foldersAlreadyPlanned = mutableSetOf<String>()

        for (record in records.filter { !it.isDirectory }) {
            val result = RuleEngine.classify(record.displayName, record.extension, projectKeywords)
            if (result.confidence < 1.0f) continue

            val folderName = result.projectFolder ?: result.category.folderName()
            if (foldersAlreadyPlanned.add(folderName)) {
                operations += PlannedOperation.CreateDirectory(
                    parent = scopeRoot,
                    name = folderName,
                    reason = "Destination folder for: ${result.reason}",
                )
            }

            val destination = FileRef.Direct("${scopeRoot.absolutePath.trimEnd('/')}/$folderName/${record.displayName}")
            operations += PlannedOperation.Move(
                source = parseFileRef(record.stableRef),
                destination = destination,
                reason = result.reason,
            )
        }

        return AgentPlan(goal = "Smart cleanup", operations = operations)
    }
}

private fun FileCategory.folderName(): String =
    name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
