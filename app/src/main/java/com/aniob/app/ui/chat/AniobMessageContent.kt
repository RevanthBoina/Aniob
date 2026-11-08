package com.aniob.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Message presentation component supporting:
 * - Collapsible intermediate thought disclosure widgets
 * - Monospace code blocks with copy-to-clipboard buttons
 * - Inline formatting (bold, backticks)
 */

private val THOUGHT_REGEX = Regex("<(?:thought|think)>([\\s\\S]*?)</(?:thought|think)>", RegexOption.IGNORE_CASE)
private val CODE_BLOCK_REGEX = Regex("```(?:(\\w+)?\\n)?([\\s\\S]*?)```")
private val INLINE_PATTERN = Regex("(\\*\\*.*?\\*\\*|`.*?`)")

@Composable
fun ThoughtDisclosureCard(
    thought: String,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Reasoning",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thought.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                )
            }
        }
    }
}

@Composable
fun CodeBlockView(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (language?.takeIf { it.isNotBlank() } ?: "code").lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MarkdownParagraph(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { parseInlineMarkdown(text) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier
    )
}

fun parseInlineMarkdown(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0
    for (match in INLINE_PATTERN.findAll(text)) {
        if (match.range.first > index) {
            builder.append(text.substring(index, match.range.first))
        }
        val matchValue = match.value
        when {
            matchValue.startsWith("**") && matchValue.endsWith("**") && matchValue.length >= 4 -> {
                val inner = matchValue.substring(2, matchValue.length - 2)
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(inner)
                builder.pop()
            }
            matchValue.startsWith("`") && matchValue.endsWith("`") && matchValue.length >= 2 -> {
                val inner = matchValue.substring(1, matchValue.length - 1)
                builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                builder.append(inner)
                builder.pop()
            }
            else -> {
                builder.append(matchValue)
            }
        }
        index = match.range.last + 1
    }
    if (index < text.length) {
        builder.append(text.substring(index))
    }
    return builder.toAnnotatedString()
}

private sealed interface ContentSegment {
    data class Text(val text: String) : ContentSegment
    data class Code(val code: String, val language: String?) : ContentSegment
}

@Composable
fun FormattedMessageView(
    content: String,
    thoughtText: String? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    // 1. Extract any <thought> or <think> tag inside content
    var extractedThought: String? = null
    var cleanedContent = content

    val match = THOUGHT_REGEX.find(content)
    if (match != null) {
        extractedThought = match.groupValues[1].trim()
        cleanedContent = content.removeRange(match.range).trim()
    }

    val finalThought = (extractedThought ?: thoughtText)?.takeIf { it.isNotBlank() }

    // 2. Parse code blocks
    val segments = remember(cleanedContent) {
        val list = mutableListOf<ContentSegment>()
        var lastIdx = 0
        for (codeMatch in CODE_BLOCK_REGEX.findAll(cleanedContent)) {
            if (codeMatch.range.first > lastIdx) {
                val sub = cleanedContent.substring(lastIdx, codeMatch.range.first).trim()
                if (sub.isNotEmpty()) {
                    list.add(ContentSegment.Text(sub))
                }
            }
            val lang = codeMatch.groupValues[1].takeIf { it.isNotBlank() }
            val code = codeMatch.groupValues[2].trim()
            list.add(ContentSegment.Code(code, lang))
            lastIdx = codeMatch.range.last + 1
        }
        if (lastIdx < cleanedContent.length) {
            val sub = cleanedContent.substring(lastIdx).trim()
            if (sub.isNotEmpty()) {
                list.add(ContentSegment.Text(sub))
            }
        }
        list
    }

    val clipboard = LocalClipboardManager.current
    var copiedAll by remember(cleanedContent) { mutableStateOf(false) }
    SelectionContainer {
    Column(modifier = modifier) {
        if (finalThought != null) {
            ThoughtDisclosureCard(thought = finalThought)
            if (segments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> {
                    MarkdownParagraph(text = segment.text, color = textColor)
                }
                is ContentSegment.Code -> {
                    CodeBlockView(code = segment.code, language = segment.language)
                }
            }
        }

        if (cleanedContent.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(cleanedContent)); copiedAll = true },
                    modifier = Modifier.testTag("copy_message")
                ) {
                    Icon(
                        if (copiedAll) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy response",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copiedAll) "Copied" else "Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    }
}
