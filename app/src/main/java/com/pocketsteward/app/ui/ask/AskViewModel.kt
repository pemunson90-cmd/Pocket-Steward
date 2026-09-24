package com.pocketsteward.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.ai.AgentModel
import com.pocketsteward.app.ai.AgentModelAvailability
import com.pocketsteward.app.ai.AskModelAnswer
import com.pocketsteward.app.content.ask.AskPassage
import com.pocketsteward.app.content.ask.AskRetrieval
import com.pocketsteward.app.content.index.ContentIndexDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AskUiState {
    data object Idle : AskUiState
    data object Searching : AskUiState

    /** Nothing has been indexed, so there is nothing to answer from. */
    data object NoIndex : AskUiState

    /** The question had no searchable words ("what is it?"). */
    data object NeedsSpecificWords : AskUiState

    data class NoMatches(val keywords: List<String>) : AskUiState

    /**
     * Passages are shown the moment they are found; [answer] fills in when
     * the model replies. [answering] is true while it is still thinking.
     */
    data class Results(
        val question: String,
        val keywords: List<String>,
        val passages: List<AskPassage>,
        val answering: Boolean,
        val answer: AskModelAnswer? = null,
        /** Why there is no model answer, when there is none. */
        val note: String? = null,
    ) : AskUiState

    data class Failed(val message: String) : AskUiState
}

/**
 * Ask your files. Read-only end to end: it reads the local content index and
 * shows text. It holds no storage gateway and cannot build a plan, so there is
 * no path from here to moving, renaming or trashing anything.
 */
class AskViewModel(
    private val dao: ContentIndexDao,
    private val model: AgentModel,
) : ViewModel() {

    private val _state = MutableStateFlow<AskUiState>(AskUiState.Idle)
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = AskUiState.Searching
            try {
                val keywords = AskRetrieval.keywords(q)
                val match = AskRetrieval.ftsQuery(keywords)
                if (match == null) {
                    _state.value = AskUiState.NeedsSpecificWords
                    return@launch
                }
                val (indexed, rows) = withContext(Dispatchers.IO) {
                    dao.countDocuments() to dao.askCandidateRows(match, CANDIDATE_LIMIT)
                }
                if (indexed == 0) {
                    _state.value = AskUiState.NoIndex
                    return@launch
                }
                val passages = withContext(Dispatchers.Default) { AskRetrieval.rank(rows, keywords) }
                if (passages.isEmpty()) {
                    _state.value = AskUiState.NoMatches(keywords)
                    return@launch
                }

                val availability = runCatching { model.availability() }.getOrDefault(AgentModelAvailability.UNAVAILABLE)
                if (availability != AgentModelAvailability.AVAILABLE) {
                    _state.value = AskUiState.Results(
                        question = q,
                        keywords = keywords,
                        passages = passages,
                        answering = false,
                        note = when (availability) {
                            AgentModelAvailability.DOWNLOADABLE, AgentModelAvailability.DOWNLOADING ->
                                "The on-device model isn't downloaded yet (Settings). Here are the best-matching passages."
                            else -> "This phone has no on-device model for answers. Here are the best-matching passages."
                        },
                    )
                    return@launch
                }

                _state.value = AskUiState.Results(q, keywords, passages, answering = true)
                val answer = withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                    model.answerFromPassages(q, passages)
                }
                _state.value = AskUiState.Results(
                    question = q,
                    keywords = keywords,
                    passages = passages,
                    answering = false,
                    answer = answer,
                    note = if (answer == null) {
                        "The model's reply couldn't be tied to these passages, so it isn't shown. The passages are."
                    } else {
                        null
                    },
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.value = AskUiState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = AskUiState.Idle
    }

    private companion object {
        const val CANDIDATE_LIMIT = 400
        const val ANSWER_TIMEOUT_MS = 90_000L
    }
}
