package com.jarvis.ai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.ai.memory.vector.HashEmbeddingProvider
import com.jarvis.ai.memory.vector.ScoredMemory
import com.jarvis.ai.memory.vector.VectorMemoryConfig
import com.jarvis.ai.memory.vector.VectorMemoryManager
import com.jarvis.ai.ui.components.GlassCard
import com.jarvis.ai.ui.theme.extended

/**
 * Responsibility: the Memories tab — a READ-ONLY view of what AURIX remembers.
 *
 * Integration rule honoured: this screen queries the EXISTING
 * [VectorMemoryManager] and never creates a second memory system, never writes,
 * and never deletes. It builds the manager with the on-device
 * [HashEmbeddingProvider] so the tab works with no provider keys configured;
 * cloud stores stay untouched when unconfigured.
 *
 * The empty state is friendly and explicit rather than a blank list.
 */
@Composable
fun MemoriesPlaceholder(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = androidx.compose.material3.MaterialTheme.extended()

    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ScoredMemory>>(emptyList()) }
    var isInitial by remember { mutableStateOf(true) }

    // Built exactly as the rest of the app builds it, but with the local hash
    // embedder so recall works offline with zero keys.
    val manager = remember {
        VectorMemoryManager(
            embedder = HashEmbeddingProvider(),
            secrets = com.jarvis.ai.provider.MapSecretsSource(), // empty: cloud stores stay unconfigured
            config = VectorMemoryConfig()
        )
    }

    // Seed the list once from the on-device fallback store.
    LaunchedEffect(Unit) {
        runCatching { manager.recall("aurix", fallbackStore = manager.localStore) }
            .onSuccess { items = it }
        isInitial = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Memories",
                color = colors.greetingPrimary,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "What Aurix remembers about you. Read-only — memories are " +
                    "written during chat, not from this screen.",
                color = colors.greetingSecondary
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Recall a topic") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = {
                // A blank query is meaningless to a vector search; the local
                // store keeps the list honest instead of showing empty.
                val q = query.trim().ifBlank { "aurix" }
                runCatching {
                    manager.recall(q, fallbackStore = manager.localStore)
                }.onSuccess { items = it }
            }) {
                Text("Search")
            }

            if (isInitial) {
                Text("Loading memories...", color = colors.greetingSecondary)
            } else if (items.isEmpty()) {
                // Friendly empty state per the spec.
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing here yet", color = colors.greetingPrimary)
                        Text(
                            text = "Tell me something to remember — for example " +
                                "\"remember that my sister's birthday is the 12th\".",
                            color = colors.greetingSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items) { memory ->
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = memory.record.content,
                                    color = colors.greetingPrimary
                                )
                                Text(
                                    text = "score ${"%.2f".format(memory.score)}",
                                    color = colors.greetingSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone) { Text("Back to Home") }
        }
    }
}
