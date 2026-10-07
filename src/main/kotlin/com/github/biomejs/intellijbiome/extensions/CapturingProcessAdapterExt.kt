package com.github.biomejs.intellijbiome.extensions

import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.KillableProcess
import java.util.concurrent.CompletableFuture

class ProcessResult( val processOutput: ProcessOutput)


fun runProcessFuture(handler: OSProcessHandler): CompletableFuture<ProcessResult> {
    val future = CompletableFuture<ProcessResult>()

    handler.addProcessListener(object : CapturingProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
            output.setExitCode(event.exitCode)
            future.complete(ProcessResult(output))
        }
    })

    future.whenComplete { _, _ ->
        if (future.isCancelled && handler.process.isAlive) {
            terminateProbeProcess(handler)
        }
    }

    handler.startNotify()

    return future
}

/** Node target handlers default to soft SIGINT; use their target-aware tree kill first. */
fun terminateProbeProcess(handler: OSProcessHandler) {
    if (!handler.process.isAlive) return
    if (handler is KillableProcess && handler.canKillProcess()) {
        handler.killProcess()
    } else {
        handler.destroyProcess()
    }
    if (handler.process.isAlive) handler.process.destroyForcibly()
}
