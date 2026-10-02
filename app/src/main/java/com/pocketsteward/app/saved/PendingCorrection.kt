package com.pocketsteward.app.saved

import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.rawValue

/** A draft learning request. It becomes a preference only for an approved matching move/copy. */
data class PendingCorrection(val rule: CorrectionRule, val sourceDestinations: Map<String, String>)

object CorrectionApprovalPolicy {
    fun editedRefs(before: List<PlannedOperation>, after: List<PlannedOperation>): Set<String> = before.zip(after).mapNotNull { (old, edited) ->
        when {
            old is PlannedOperation.Move && edited is PlannedOperation.Move && old.destination != edited.destination -> old.source.rawValue()
            old is PlannedOperation.Copy && edited is PlannedOperation.Copy && old.destination != edited.destination -> old.source.rawValue()
            else -> null
        }
    }.toSet()
    fun propose(records: List<com.pocketsteward.app.data.db.FileRecord>, operations: List<PlannedOperation>, refs: Set<String>, owner: String, homePath: String? = null): List<PendingCorrection> {
        val sources = records.filter { it.stableRef in refs }
        val term = com.pocketsteward.app.filing.ProjectEvidenceTerms.learnFilenameTerm(sources.map { it.displayName }) ?: return emptyList()
        val destinations = operations.mapNotNull { operation -> when (operation) {
            is PlannedOperation.Move -> operation.source.rawValue() to operation.destination.rawValue()
            is PlannedOperation.Copy -> operation.source.rawValue() to operation.destination.rawValue()
            else -> null
        } }.toMap()
        return sources.groupBy { it.parentRef }.mapNotNull { (parent, group) ->
            if (parent == null) return@mapNotNull null
            val rule = CorrectionRulePolicy.clean(CorrectionRule(term, owner, parent, homePath)) ?: return@mapNotNull null
            val selected = group.mapNotNull { record -> destinations[record.stableRef]?.let { record.stableRef to it } }.toMap()
            selected.takeIf { it.isNotEmpty() }?.let { PendingCorrection(rule, it) }
        }
    }

    fun merge(existing: List<PendingCorrection>, additions: List<PendingCorrection>): List<PendingCorrection> =
        (additions + existing).groupBy { CorrectionRulePolicy.identity(it.rule) }.values.map { sameRule ->
            sameRule.first().copy(sourceDestinations = sameRule.asReversed().flatMap { it.sourceDestinations.entries }.associate { it.toPair() })
        }.take(100)

    fun approved(pending: List<PendingCorrection>, selected: List<PlannedOperation>): List<CorrectionRule> {
        val destinations = selected.mapNotNull { operation -> when (operation) {
            is PlannedOperation.Move -> operation.source.rawValue() to operation.destination.rawValue()
            is PlannedOperation.Copy -> operation.source.rawValue() to operation.destination.rawValue()
            else -> null
        } }.toMap()
        return pending.filter { request -> request.sourceDestinations.any { (ref, destination) -> destinations[ref] == destination } }
            .mapNotNull { CorrectionRulePolicy.clean(it.rule) }.distinctBy(CorrectionRulePolicy::identity)
    }
}
