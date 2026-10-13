package dev.dreamz.llmwizard.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.dreamz.llmwizard.core.knowledge.Article
import dev.dreamz.llmwizard.core.knowledge.ContextualHelp
import dev.dreamz.llmwizard.core.knowledge.KnowledgeBase
import dev.dreamz.llmwizard.ui.MonoBlock

/** BUILD_PLAN E5: the knowledge base, searchable and by category. */
@Composable
fun LearnTab(kb: KnowledgeBase, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search articles") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        }
        if (q.isNotBlank()) {
            val hits = kb.search(q)
            if (hits.isEmpty()) item { Text("Nothing found.") }
            items(hits, key = { "s" + it.id }) { ArticleCard(it) { onOpen(it.id) } }
        } else {
            dev.dreamz.llmwizard.core.knowledge.ArticleCategory.entries.forEach { cat ->
                val list = kb.byCategory(cat)
                if (list.isNotEmpty()) {
                    item(key = "h" + cat.name) { Text(cat.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.id }) { ArticleCard(it) { onOpen(it.id) } }
                }
            }
        }
    }
}

@Composable
fun ArticleCard(a: Article, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall)
            Text(a.summary, style = MaterialTheme.typography.bodySmall)
            Text("${a.readMinutes} min · ${a.difficulty.name.lowercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(kb: KnowledgeBase, id: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val a = kb[id]
    Scaffold(topBar = { TopAppBar(title = { Text(a?.title ?: "Not found", maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (a == null) { Text("This article is not in this version of the app."); return@Column }
            Text("${a.readMinutes} min read · ${a.difficulty.name.lowercase()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Markdown(a.markdown)
            val related = kb.related(a)
            if (related.isNotEmpty()) {
                Text("Related", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                related.forEach { r -> ArticleCard(r) { onOpen(r.id) } }
            }
        }
    }
}

/** Enough Markdown for the articles: headings, paragraphs, bullets, quotes, tables, **bold**, *italic*, `code`. */
@Composable
fun Markdown(md: String) {
    val blocks = md.split(Regex("\\n\\s*\\n"))
    blocks.forEach { block ->
        val b = block.trim()
        when {
            b.startsWith("|") -> MonoBlock(b.lines().filterNot { it.replace("|", "").replace("-", "").isBlank() }.joinToString("\n") { line ->
                line.trim().trim('|').split('|').joinToString("  ") { it.trim() } }.replace("**", ""))
            b.startsWith("```") -> MonoBlock(b.removePrefix("```").substringAfter('\n').removeSuffix("```").trimEnd())
            b.startsWith("#") -> Text(inline(b.trimStart('#').trim()), style = if (b.startsWith("###")) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
            b.startsWith(">") -> Card(Modifier.fillMaxWidth()) { Text(inline(b.lines().joinToString(" ") { it.removePrefix(">").trim() }), Modifier.padding(12.dp), fontStyle = FontStyle.Italic) }
            b.lines().all { it.trimStart().startsWith("- ") || it.trimStart().startsWith("* ") || !it.startsWith(" ").not() } && (b.startsWith("- ") || b.startsWith("* ")) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bullets(b).forEach { item -> Row { Text("•  "); Text(inline(item)) } }
                }
            else -> Text(inline(b.lines().joinToString(" ") { it.trim() }), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun bullets(b: String): List<String> {
    val out = mutableListOf<String>()
    b.lines().forEach { l ->
        val t = l.trim()
        if (t.startsWith("- ") || t.startsWith("* ")) out += t.drop(2) else if (out.isNotEmpty()) out[out.size - 1] = out.last() + " " + t
    }
    return out
}

fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, e)) }; i = e + 1 }
            s[i] == '*' && s.indexOf('*', i + 1) > 0 && s.getOrNull(i + 1)?.isWhitespace() == false -> { val e = s.indexOf('*', i + 1); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1 }
            else -> { append(s[i]); i++ }
        }
    }
}

/** BUILD_PLAN E5.3: the "?" on each tab, opening the articles that explain that screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpFab(screen: String, kb: KnowledgeBase, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val articles = ContextualHelp.forScreen(screen, kb)
    if (articles.isEmpty()) return
    SmallFloatingActionButton({ open = true }) { Text("?", style = MaterialTheme.typography.titleLarge) }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("About this screen", style = MaterialTheme.typography.titleMedium)
            articles.forEach { a -> ArticleCard(a) { open = false; onOpen(a.id) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnRoute(kb: KnowledgeBase, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Learn") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad)) { LearnTab(kb, onOpen) }
    }
}
