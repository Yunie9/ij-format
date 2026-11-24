package org.ijformat.mcp

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSchemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.function.Consumer

internal object IdeaFormatter {
    private const val MAX_SOURCE_BYTES = 128 * 1024

    suspend fun format(language: String, source: String, startLine: Int, endLine: Int): String {
        val name = when (language) {
            "java" -> "Input.java"
            "kotlin" -> "Input.kt"
            else -> throw IllegalArgumentException("Expected language java or kotlin")
        }
        require(source.isNotEmpty()) { "Source is empty" }
        require(source.length <= MAX_SOURCE_BYTES && source.toByteArray().size <= MAX_SOURCE_BYTES) {
            "Source exceeds 128 KiB"
        }
        val crlf = source.contains("\r\n")
        val bare = if (crlf) source.replace("\r\n", "") else source
        require(!bare.contains('\r') && (!crlf || !bare.contains('\n'))) {
            "Expected uniform LF or CRLF line endings"
        }
        val text = if (crlf) source.replace("\r\n", "\n") else source
        val range = lineRange(text, startLine, endLine)

        val formatted = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            val project = ProjectManager.getInstance().defaultProject
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
            val file = PsiFileFactory.getInstance(project).createFileFromText(name, fileType, text)
            check(!file.isPhysical) { "Expected a non-physical PSI file" }
            require(file.language.id.equals(language, ignoreCase = true)) {
                "IntelliJ language support for $language is unavailable"
            }
            val scheme = CodeStyleSchemes.getInstance().currentScheme
            CodeStyle.runWithLocalSettings(project, scheme.codeStyleSettings, Consumer {
                WriteCommandAction.runWriteCommandAction(project) {
                    CodeStyleManager.getInstance(project).reformatText(file, listOf(range))
                }
            })
            file.text
        }

        val before = text.substring(0, range.startOffset)
        val after = text.substring(range.endOffset)
        require(formatted.length >= before.length + after.length &&
            formatted.startsWith(before) && formatted.endsWith(after)) {
            "IntelliJ would change text outside the selected lines"
        }
        return if (crlf) formatted.replace("\n", "\r\n") else formatted
    }

    private fun lineRange(text: String, first: Int, last: Int): TextRange {
        require(first >= 1 && last >= first) { "Expected a 1-based inclusive line range" }
        var line = 1
        var offset = 0
        var start = 0
        while (offset < text.length) {
            if (line == first) start = offset
            val newline = text.indexOf('\n', offset)
            val end = if (newline >= 0) newline else text.length
            if (line == last) return TextRange(start, end)
            offset = end + 1
            line++
        }
        throw IllegalArgumentException("Line range exceeds source")
    }
}
