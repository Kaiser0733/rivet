package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.GrantPath
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.serialization.Serializable

internal const val PRESENT_DELIVERABLES_RULE =
    "Before your final reply, use present for finished files the user asked you to deliver, such as documents, pages and code files. Leave out scratch files, build artifacts and incidental project edits."

@Serializable
data class PresentedFile(val path: String, val description: String)

@Serializable
internal data class PresentArguments(val files: List<PresentedFile>)

internal fun presentablePath(value: String): WorkspacePath {
    val path = WorkspacePath.parse(value)
    require(!path.isRoot && !GrantPath.isExternal(value))
    return path
}

internal suspend fun verifyPresentedFiles(
    entries: List<PresentedFile>,
    stat: suspend (String) -> AgentWorkspaceEntry,
): List<PresentedFile> {
    require(entries.size in 1..4 && entries.map { it.path }.distinct().size == entries.size)
    entries.forEach { file ->
        presentablePath(file.path)
        require(file.description.isNotBlank() && file.description.length <= 200 &&
            file.description.none(Char::isISOControl))
    }
    return entries.map { file ->
        val entry = try {
            stat(file.path)
        } catch (failure: AgentWorkspaceFailure) {
            throw PresentationFailure(failure.code, file.path)
        }
        if (entry.directory) throw PresentationFailure("not_file", file.path)
        file.copy(path = entry.path)
    }
}

internal class PresentationFailure(val code: String, val path: String) : Exception(code)
