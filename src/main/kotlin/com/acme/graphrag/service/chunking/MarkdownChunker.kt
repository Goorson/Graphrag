package com.acme.graphrag.service.chunking

import com.acme.graphrag.domain.TextChunk
import org.springframework.stereotype.Component

@Component
class MarkdownChunker {

    companion object {
        private const val MAX_CHUNK_CHARS = 2000
        private const val OVERLAP_CHARS = 100
    }

    fun chunk(markdown: String): List<TextChunk> {
        val sections = splitIntoSections(markdown)
        if (sections.isEmpty()) {
            return splitLongText(null, markdown.trim()).mapIndexed { index, text ->
                TextChunk(chunkIndex = index, section = null, content = text)
            }
        }

        val chunks = mutableListOf<TextChunk>()
        var index = 0
        for ((sectionTitle, sectionBody) in sections) {
            val parts = splitLongText(sectionTitle, sectionBody.trim())
            for (part in parts) {
                if (part.isBlank()) continue
                chunks += TextChunk(chunkIndex = index++, section = sectionTitle, content = part)
            }
        }
        return chunks
    }

    private fun splitIntoSections(markdown: String): List<Pair<String?, String>> {
        val lines = markdown.lines()
        val sections = mutableListOf<Pair<String?, String>>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            if (currentBody.isNotBlank() || currentTitle != null) {
                sections += currentTitle to currentBody.toString()
            }
            currentBody.clear()
        }

        for (line in lines) {
            val heading = headingTitle(line)
            if (heading != null) {
                flush()
                currentTitle = heading
            } else {
                currentBody.appendLine(line)
            }
        }
        flush()

        if (sections.isEmpty() && markdown.isNotBlank()) {
            sections += null to markdown
        }
        return sections
    }

    private fun headingTitle(line: String): String? {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("### ") -> trimmed.removePrefix("### ").trim()
            trimmed.startsWith("## ") -> trimmed.removePrefix("## ").trim()
            trimmed.startsWith("# ") -> trimmed.removePrefix("# ").trim()
            else -> null
        }
    }

    private fun splitLongText(section: String?, text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) {
            return listOf(text)
        }

        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + MAX_CHUNK_CHARS, text.length)
            parts += text.substring(start, end).trim()
            if (end >= text.length) break
            start = maxOf(end - OVERLAP_CHARS, start + 1)
        }
        return parts.filter { it.isNotBlank() }
    }
}
