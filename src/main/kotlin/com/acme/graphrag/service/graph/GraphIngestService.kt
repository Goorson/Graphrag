package com.acme.graphrag.service.graph

import com.acme.graphrag.domain.Document
import com.acme.graphrag.domain.DocumentStatus
import com.acme.graphrag.domain.GraphStatus
import com.acme.graphrag.repository.ChunkRepository
import com.acme.graphrag.repository.DocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GraphIngestService(
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val entityExtractionService: EntityExtractionService,
    private val extractionMergeService: ExtractionMergeService,
    private val graphWriteService: GraphWriteService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ingestDocument(documentId: UUID) {
        val document = documentRepository.findById(documentId)
            ?: throw IllegalArgumentException("Dokument nie istnieje: $documentId")

        if (document.status != DocumentStatus.INDEXED) {
            documentRepository.updateGraphStatus(documentId, GraphStatus.SKIPPED)
            return
        }

        try {
            val batches = chunkRepository.getChunkBatches(documentId)
            require(batches.isNotEmpty()) { "Brak treści chunków dla dokumentu $documentId" }

            val documentKind = when {
                document.mimeType.contains("pdf") -> "PDF (wykład/notatki)"
                document.mimeType.contains("markdown") -> "dokument Markdown"
                else -> "dokument"
            }

            val extractions = batches.map { batch ->
                entityExtractionService.extract(batch, documentKind)
            }
            val merged = extractionMergeService.merge(extractions)

            graphWriteService.rebuildDocumentGraph(document, merged)
            documentRepository.updateGraphStatus(documentId, GraphStatus.INDEXED)
            log.info(
                "graph_ingest_completed documentId={} entities={} relationships={} batches={}",
                documentId,
                merged.entities.size,
                merged.relationships.size,
                batches.size,
            )
        } catch (ex: Exception) {
            documentRepository.updateGraphStatus(documentId, GraphStatus.FAILED)
            throw ex
        }
    }

    fun rebuild(documentId: UUID) {
        ingestDocument(documentId)
    }
}
