package com.acme.graphrag.util

object VectorUtils {

    fun toPgVectorLiteral(embedding: FloatArray): String {
        val values = embedding.joinToString(separator = ",") { value ->
            if (value.isNaN() || value.isInfinite()) "0" else value.toString()
        }
        return "[$values]"
    }
}
