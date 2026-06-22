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

                val parts = splitPageText(pageText)
                for (part in parts) {
                    chunks += TextChunk(
                        chunkIndex = index++,
                        section = "Strona $pageNum",
                        page = pageNum,
                        content = part,
                    )
                }
            }

            if (chunks.isEmpty()) {
                return PdfExtractResult(chunks = emptyList(), ocrRequired = true)
            }
            return PdfExtractResult(chunks = chunks, ocrRequired = false)
        }
    }

    private fun splitPageText(text: String, maxChars: Int = 2000, overlap: Int = 100): List<String> {
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
