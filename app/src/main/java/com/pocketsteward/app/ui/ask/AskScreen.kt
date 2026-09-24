package com.pocketsteward.app.ui.ask

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.ai.AskModelAnswer
import com.pocketsteward.app.content.ask.AskPassage
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.ui.components.FileVisual
import com.pocketsteward.app.ui.scan.openDirectFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: AskViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AskViewModel(
                    dao = ContentSearchDatabase.getInstance(context.applicationContext).contentIndexDao(),
                    model = container.agentModel,
                )
            }
        },
    )
    val state by viewModel.state.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        viewModel.ask(question)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask your files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding(),
        ) {
            Text(
                "Answers come only from files already in your content index, with the passages they came from. Read-only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("When does my lease end?") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                Button(onClick = submit, enabled = question.isNotBlank() && state !is AskUiState.Searching) {
                    Text("Ask")
                }
            }

            when (val s = state) {
                AskUiState.Idle -> Hint(
                    "Try a question about something inside your documents: a date, an amount, a name, a clause.",
                )
                AskUiState.Searching -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                AskUiState.NoIndex -> Hint(
                    "Nothing has been indexed yet. Search for text inside files once (for example, from Home: " +
                        "\"find documents containing lease\") and that folder gets indexed. Then ask here.",
                )
                AskUiState.NeedsSpecificWords -> Hint(
                    "Add at least one specific word to look for, like a name, place, amount or topic.",
                )
                is AskUiState.NoMatches -> Hint(
                    "No indexed file mentions ${s.keywords.joinToString(", ") { "\"$it\"" }}. " +
                        "Try other words, or index the folder that holds the answer.",
                )
                is AskUiState.Failed -> Hint("Couldn't search the index: ${s.message}")
                is AskUiState.Results -> Results(s, onOpen = { p ->
                    openDirectFile(context, p.stableRef, p.displayName, p.extension)
                })
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun Results(state: AskUiState.Results, onOpen: (AskPassage) -> Unit) {
    val cited = (state.answer as? AskModelAnswer.Answered)?.citations.orEmpty()
    val citedPassages = cited.mapNotNull { n -> state.passages.firstOrNull { it.number == n } }
    val others = state.passages.filter { it.number !in cited }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "answer") {
            AnswerCard(state)
        }
        if (citedPassages.isNotEmpty()) {
            item(key = "cited-h") { SectionLabel("Sources") }
            items(citedPassages, key = { "c${it.number}" }) { p ->
                PassageCard(p, state.keywords, emphasised = true, onOpen = { onOpen(p) }, modifier = Modifier.animateItem())
            }
        }
        if (others.isNotEmpty()) {
            item(key = "other-h") {
                SectionLabel(if (citedPassages.isEmpty()) "Best-matching passages" else "Other matches")
            }
            items(others, key = { "o${it.number}" }) { p ->
                PassageCard(p, state.keywords, emphasised = false, onOpen = { onOpen(p) }, modifier = Modifier.animateItem())
            }
        }
        item(key = "pad") { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun AnswerCard(state: AskUiState.Results) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(state.question, style = MaterialTheme.typography.titleSmall)
            when {
                state.answering -> Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Reading the passages below…", style = MaterialTheme.typography.bodyMedium)
                }
                state.answer is AskModelAnswer.Answered -> Text(
                    text = state.answer.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state.answer == AskModelAnswer.NotFound -> Text(
                    "These passages don't answer that. They're the closest matches in your index.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                else -> Text(
                    state.note ?: "No answer.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun PassageCard(
    passage: AskPassage,
    keywords: List<String>,
    emphasised: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = if (emphasised) CardDefaults.cardColors() else CardDefaults.outlinedCardColors(),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileVisual(name = passage.displayName, location = passage.stableRef)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "[${passage.number}] ${passage.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        passage.pageNumber?.let { append("Page $it") }
                        if (passage.ocr) append(if (isEmpty()) "Scanned text" else " · scanned text")
                        passage.parentRef?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = highlighted(passage.excerpt, keywords),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Bolds each keyword occurrence so it's clear why the passage was picked. */
private fun highlighted(text: String, keywords: List<String>) = buildAnnotatedString {
    val lower = text.lowercase()
    val marks = BooleanArray(text.length)
    for (k in keywords) {
        var i = lower.indexOf(k)
        while (i >= 0) {
            for (j in i until (i + k.length).coerceAtMost(text.length)) marks[j] = true
            i = lower.indexOf(k, i + k.length)
        }
    }
    var i = 0
    while (i < text.length) {
        val bold = marks[i]
        var j = i
        while (j < text.length && marks[j] == bold) j++
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text, i, j) } else append(text, i, j)
        i = j
    }
}
