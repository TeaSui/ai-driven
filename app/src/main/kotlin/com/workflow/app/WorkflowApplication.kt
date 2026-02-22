package com.workflow.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.workflow"])
class WorkflowApplication

fun main(args: Array<String>) {
    runApplication<WorkflowApplication>(*args)
}
