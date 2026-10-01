package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ask.AskCandidateRow
import com.pocketsteward.app.content.index.ContentIndexDao
import com.pocketsteward.app.ui.ask.AskUiState
import com.pocketsteward.app.ui.ask.AskViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelCancellationTest {
    private val rows = listOf(AskCandidateRow(1, "/Download/a.txt", "a.txt", "/Download", "txt", null, false,
        "Lilith manuscript and NSTL notes", 100, 20))
    private fun dao(): ContentIndexDao = Proxy.newProxyInstance(ContentIndexDao::class.java.classLoader,
        arrayOf(ContentIndexDao::class.java)) { _, method, _ ->
        when (method.name) { "countDocuments" -> 1; "askCandidateRows" -> rows; else -> error(method.name) }
    } as ContentIndexDao

    @Test fun lateAvailabilityCannotReplaceANewerQuestion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val delayed = CompletableDeferred<Unit>()
        var calls = 0
        val model = Model {
            if (++calls == 1) withContext(NonCancellable) { delayed.await() }
            AgentModelAvailability.UNAVAILABLE
        }
        val viewModel = AskViewModel(dao(), model, { it }, dispatcher, dispatcher)
        try {
            viewModel.ask("Lilith"); runCurrent()
            viewModel.ask("NSTL"); runCurrent()
            assertThat((viewModel.state.value as AskUiState.Results).question).isEqualTo("NSTL")
            delayed.complete(Unit); runCurrent()
            assertThat((viewModel.state.value as AskUiState.Results).question).isEqualTo("NSTL")
        } finally { delayed.complete(Unit); viewModel.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun clearStaysClearWhenAnUncooperativeModelCompletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val delayed = CompletableDeferred<Unit>()
        val model = Model { withContext(NonCancellable) { delayed.await() }; AgentModelAvailability.UNAVAILABLE }
        val viewModel = AskViewModel(dao(), model, { it }, dispatcher, dispatcher)
        try {
            viewModel.ask("Lilith"); runCurrent()
            viewModel.clear()
            delayed.complete(Unit); runCurrent()
            assertThat(viewModel.state.value).isEqualTo(AskUiState.Idle)
        } finally { delayed.complete(Unit); viewModel.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun unverifiedPassagesNeverReachTheModel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var calls = 0
        val viewModel = AskViewModel(dao(), Model { calls++; AgentModelAvailability.UNAVAILABLE }, { emptyList() }, dispatcher, dispatcher)
        try {
            viewModel.ask("Lilith"); runCurrent()
            assertThat(viewModel.state.value).isInstanceOf(AskUiState.NoMatches::class.java)
            assertThat(calls).isEqualTo(0)
        } finally { viewModel.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun aSourceChangedWhileAnsweringCannotSupplyALateAnswer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val delayed = CompletableDeferred<Unit>()
        var checks = 0
        val viewModel = AskViewModel(dao(), Model(answer = { delayed.await(); null }) { AgentModelAvailability.AVAILABLE },
            { if (++checks == 1) it else emptyList() }, dispatcher, dispatcher)
        try {
            viewModel.ask("Lilith"); runCurrent()
            assertThat((viewModel.state.value as AskUiState.Results).answering).isTrue()
            delayed.complete(Unit); runCurrent()
            assertThat(viewModel.state.value).isInstanceOf(AskUiState.NoMatches::class.java)
            assertThat(checks).isEqualTo(2)
        } finally { delayed.complete(Unit); viewModel.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private class Model(private val answer: suspend () -> AskModelAnswer? = { null },
        private val status: suspend () -> AgentModelAvailability) : AgentModel {
        override suspend fun availability() = status()
        override fun download() = emptyFlow<AgentModelDownloadState>()
        override suspend fun coherenceAudit(scopeLabel: String, documents: List<SemanticDocument>): CoherenceAuditResult = error("Not used")
        override suspend fun answerFromPassages(question: String, passages: List<com.pocketsteward.app.content.ask.AskPassage>) = answer()
    }
}
