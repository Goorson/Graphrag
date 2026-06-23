package com.acme.graphrag.service

import com.acme.graphrag.repository.DocumentRepository
import com.acme.graphrag.service.graph.GraphWriteService
import com.acme.graphrag.util.ProjectPaths
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.util.UUID

@Service
class DocumentDeleteService(
    private val documentRepository: DocumentRepository,
    private val graphWriteService: GraphWriteService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun delete(documentId: UUID): Boolean {
        val document = documentRepository.findById(documentId) ?: return false

        try {
            graphWriteService.deleteDocumentNode(documentId)
        } catch (ex: Exception) {
            log.warn("Neo4j cleanup failed for document {}: {}", documentId, ex.message)
        }

        try {
            val filePath = ProjectPaths.resolveRelative(document.path)
            Files.deleteIfExists(filePath)
        } catch (ex: Exception) {
            log.warn("File delete failed for {}: {}", document.path, ex.message)
        }

        return documentRepository.deleteById(documentId)
    }
}
