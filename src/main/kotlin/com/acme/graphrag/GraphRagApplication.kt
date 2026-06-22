package com.acme.graphrag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class GraphRagApplication

fun main(args: Array<String>) {
    runApplication<GraphRagApplication>(*args)
}
