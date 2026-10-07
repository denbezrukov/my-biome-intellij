package com.github.biomejs.intellijbiome.lsp

import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal class BiomeDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getMessage(diagnostic: Diagnostic): String {
        val text = super.getMessage(diagnostic).ifEmpty {
            // New SDKs changed Diagnostic.getMessage's JVM return type to Either, while the
            // binary-stable platform helper returns empty text for MarkupContent. Read only
            // that missing case reflectively so this plugin still links against SDK 253.
            val message = messageGetter.invoke(diagnostic) as? Either<*, *>
            (message?.right as? MarkupContent)?.value.orEmpty()
        }
        val code = diagnostic.code?.let { it.left ?: it.right?.toString() }
        return "Biome: $text" + (code?.let { " ($it)" } ?: "")
    }

    override fun getTooltip(diagnostic: Diagnostic): String =
        StringUtil.escapeXmlEntities(getMessage(diagnostic))
            .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>")

    private companion object {
        val messageGetter = Diagnostic::class.java.getMethod("getMessage")
    }
}
