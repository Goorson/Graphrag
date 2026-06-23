package com.acme.graphrag.service

import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.DocumentStatus
import com.acme.graphrag.domain.GraphStatus
import com.acme.graphrag.domain.TextChunk
import com.acme.graphrag.repository.ChunkRepository
import com.acme.graphrag.repository.DocumentRepository
import com.acme.graphrag.service.chunking.MarkdownChunker
import com.acme.graphrag.service.chunking.PdfTextExtractor
import com.acme.graphrag.util.ProjectPaths
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.io.path.extension

@Service
class IngestService(
    private val markdownChunker: MarkdownChunker,
    private val pdfTextExtractor: PdfTextExtractor,
    private val embeddingService: EmbeddingService,
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
) {

    fun ingestFromPath(relativePath: String): IngestResult {
        val normalizedPath = relativePath.replace('\\', '/')
        val filePath = resolveProjectPath(normalizedPath)
        require(Files.exists(filePath)) { "Plik nie istnieje: $normalizedPath" }

        val fileHash = sha256(Files.readAllBytes(filePath))
        documentRepository.findByPath(normalizedPath)?.let { existing ->
            if (existing.contentHash == fileHash) {
                return IngestResult(
                    documentId = existing.id,
                    chunksCreated = 0,
                    filename = normalizedPath,
                    skipped = true,
                )
            }
        }

        return when {
            normalizedPath.endsWith(".md", ignoreCase = true) -> {
                val content = Files.readString(filePath, StandardCharsets.UTF_8)
                val filename = normalizedPath.substringAfterLast('/')
                ingestMarkdown(normalizedPath, filename, content, fileHash)
            }
            normalizedPath.endsWith(".pdf", ignoreCase = true) -> {
                val filename = normalizedPath.substringAfterLast('/')
                ingestPdf(normalizedPath, filename, filePath, fileHash)
            }
            else -> throw IllegalArgumentException("Obsługiwane rozszerzenia: .md, .pdf")
        }
    }

    fun ingestFolder(
        relativeFolder: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<IngestResult> {
        val root = resolveProjectPath(relativeFolder)
        require(Files.isDirectory(root)) { "Folder nie istnieje: $relativeFolder" }

        val discoveredFiles = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val ext = file.extension.lowercase()
                    if (ext == "md" || ext == "pdf") {
                        discoveredFiles.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        val projectRoot = Path.of(System.getProperty("user.dir")).normalize()
        val results = mutableListOf<IngestResult>()
        val total = discoveredFiles.size
        discoveredFiles.forEachIndexed { index, file ->
            val relative = projectRoot.relativize(file.normalize()).toString().replace('\\', '/')
            results += ingestFromPath(relative)
            onProgress(index + 1, total)
        }
        return results
    }

    private fun ingestMarkdown(path: String, filename: String, content: String, contentHash: String): IngestResult {
        val chunks = markdownChunker.chunk(content)
        require(chunks.isNotEmpty()) { "Nie udało się podzielić dokumentu na chunki" }
        return persistDocument(
            path = path,
            filename = filename,
            mimeType = "text/markdown",
            contentHash = contentHash,
            status = DocumentStatus.INDEXED,
            chunks = chunks,
        )
    }

    private fun ingestPdf(path: String, filename: String, filePath: Path, contentHash: String): IngestResult {
        val extracted = pdfTextExtractor.extractChunks(filePath)
        if (extracted.ocrRequired) {
            documentRepository.deleteByPath(path)
            val documentId = UUID.randomUUID()
            documentRepository.insert(
                Document(
                    id = documentId,
                    filename = filename,
                    path = path,
                    mimeType = "application/pdf",
                    contentHash = contentHash,
                    status = DocumentStatus.SKIPPED_OCR_REQUIRED,
                    graphStatus = GraphStatus.SKIPPED,
                    ingestedAt = Instant.now(),
                ),
            )
            return IngestResult(documentId = documentId, chunksCreated = 0, filename = path, skipped = true)
        }
        return persistDocument(
            path = path,
            filename = filename,
            mimeType = "application/pdf",
            contentHash = contentHash,
            status = DocumentStatus.INDEXED,
            chunks = extracted.chunks,
        )
    }

    private fun persistDocument(
        path: String,
        filename: String,
        mimeType: String,
        contentHash: String,
        status: DocumentStatus,
        chunks: List<TextChunk>,
    ): IngestResult {
        documentRepository.deleteByPath(path)

        val documentId = UUID.randomUUID()
        documentRepository.insert(
            Document(
                id = documentId,
                filename = filename,
                path = path,
                mimeType = mimeType,
                contentHash = contentHash,
                status = status,
                ingestedAt = Instant.now(),
            ),
        )

        if (status != DocumentStatus.INDEXED || chunks.isEmpty()) {
            return IngestResult(documentId = documentId, chunksCreated = 0, filename = path)
        }

        val embeddings = embeddingService.embed(chunks.map { it.content })
        chunks.zip(embeddings).forEach { (chunk, embedding) ->
            chunkRepository.insert(
                id = UUID.randomUUID(),
                documentId = documentId,
                chunkIndex = chunk.chunkIndex,
                section = chunk.section,
                page = chunk.page,
                content = chunk.content,
                embedding = embedding,
            )
        }

        return IngestResult(
            documentId = documentId,
            chunksCreated = chunks.size,
            filename = path,
        )
    }

    private fun resolveProjectPath(relativePath: String): Path =
        ProjectPaths.resolveRelative(relativePath)

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class IngestResult(
    val documentId: UUID,
    val chunksCreated: Int,
    val filename: String,
    val skipped: Boolean = false,
)
