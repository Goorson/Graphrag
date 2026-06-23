package com.acme.graphrag.service.chunking

import com.acme.graphrag.domain.TextChunk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class PdfTextExtractor {

    fun extractChunks(pdfPath: Path): PdfExtractResult {
        val bytes = Files.readAllBytes(pdfPath)
        Loader.loadPDF(bytes).use { document ->
            val stripper = PDFTextStripper()
            val chunks = mutableListOf<TextChunk>()
            var index = 0

            for (pageNum in 1..document.numberOfPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(document).trim()
                if (pageText.isBlank()) continue

                val sections = splitIntoSections(pageText)
                for ((sectionTitle, sectionText) in sections) {
                    val parts = splitPageText(sectionText)
                    for (part in parts) {
                        chunks += TextChunk(
                            chunkIndex = index++,
                            section = "Strona $pageNum · $sectionTitle",
                            page = pageNum,
                            content = part,
                        )
                    }
                }
            }

            if (chunks.isEmpty()) {
                return PdfExtractResult(chunks = emptyList(), ocrRequired = true)
            }
            return PdfExtractResult(chunks = chunks, ocrRequired = false)
        }
    }

    private val sectionHeaderPattern = Regex(
        """(?im)^(experience|work experience|employment history|employment|doświadczenie|doswiadczenie|work history|education|wykształcenie|wyksztalcenie|projects|projekty|skills|umiejętności|umiejetnosci|summary|podsumowanie)\s*:?\s*$""",
    )

    private fun splitIntoSections(pageText: String): List<Pair<String, String>> {
        val lines = pageText.lines()
        if (lines.none { sectionHeaderPattern.containsMatchIn(it.trim()) }) {
            return listOf("Treść" to pageText)
        }

        val sections = mutableListOf<Pair<String, String>>()
        var currentTitle = "Nagłówek"
        val currentLines = mutableListOf<String>()

        fun flush() {
            val content = currentLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                sections += currentTitle to content
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && sectionHeaderPattern.matches(trimmed)) {
                flush()
                currentTitle = trimmed.trimEnd(':').replaceFirstChar { it.titlecase() }
                currentLines.clear()
            } else {
                currentLines += line
            }
        }
        flush()

        return sections.ifEmpty { listOf("Treść" to pageText) }
    }

    private fun splitPageText(text: String, maxChars: Int = 1200, overlap: Int = 150): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxChars, text.length)
            parts += text.substring(start, end).trim()
            if (end >= text.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return parts.filter { it.isNotBlank() }
    }
}

data class PdfExtractResult(
    val chunks: List<TextChunk>,
    val ocrRequired: Boolean,
)
