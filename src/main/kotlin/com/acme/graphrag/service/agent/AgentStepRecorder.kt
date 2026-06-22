package com.acme.graphrag.service.agent

import com.acme.graphrag.service.SourceCitation
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class RecordedAgentStep(
    val stepIndex: Int,
    val toolName: String,
    val toolInput: Map<String, Any?>,
    val toolOutput: String,
    val outputSummary: String,
    val durationMs: Long,
)

class AgentLoopException(message: String) : RuntimeException(message)

@Component
class AgentStepRecorder {

    private val context = ThreadLocal<RecordingContext?>()

    fun begin(sessionId: UUID) {
        context.set(RecordingContext(sessionId))
    }

    fun end(): RecordingContext? {
        val ctx = context.get()
        context.remove()
        return ctx
    }

    fun checkLoop(toolName: String, input: String) {
        val ctx = context.get() ?: return
        val signature = "$toolName:${input.hashCode()}"
        val count = ctx.signatures.count { it == signature }
        if (count >= 2) {
            throw AgentLoopException("Przerwano: powtarzające się wywołanie narzędzia $toolName.")
        }
        ctx.signatures.add(signature)
    }

    fun record(
        toolName: String,
        toolInput: Map<String, Any?>,
        toolOutput: String,
        outputSummary: String,
        durationMs: Long,
    ) {
        val ctx = context.get() ?: return
        val stepIndex = ctx.stepCounter.getAndIncrement()
        ctx.steps.add(
            RecordedAgentStep(
                stepIndex = stepIndex,
                toolName = toolName,
                toolInput = toolInput,
                toolOutput = toolOutput,
                outputSummary = outputSummary,
                durationMs = durationMs,
            ),
        )
    }

    fun addSources(sources: List<SourceCitation>) {
        val ctx = context.get() ?: return
        ctx.sources.addAll(sources)
    }

    data class RecordingContext(
        val sessionId: UUID,
        val stepCounter: AtomicInteger = AtomicInteger(0),
        val steps: MutableList<RecordedAgentStep> = mutableListOf(),
        val signatures: MutableList<String> = mutableListOf(),
        val sources: MutableList<SourceCitation> = mutableListOf(),
    )
}
