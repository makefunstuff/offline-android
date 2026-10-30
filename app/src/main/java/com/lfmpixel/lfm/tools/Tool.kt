package com.lfmpixel.lfm.tools

import com.lfmpixel.lfm.llm.ToolSpec
import com.lfmpixel.lfm.settings.ToolPolicy

/** A field on the confirmation sheet: the exact who, what or when. */
data class Field(val key: String, val label: String, val value: String, val editable: Boolean = true, val highlight: Boolean = false)

/** What the owner sees before an action runs. */
data class Preview(
    val title: String,             // "Add to calendar?"
    val icon: String,              // icon name, see ui/components/Icons.kt
    val confirmLabel: String,      // "Add", "Send", "Call"
    val fields: List<Field>,
    val note: String = "",
)

/** What a tool did, for the chat card, the model and the action history. */
data class ToolResult(
    val ok: Boolean,
    val forModel: String,          // goes back to the model inside <tool_response>
    val cardLabel: String = "",    // "Reminder set"
    val cardTitle: String = "",
    val cardDetail: String = "",
    val undo: String = "",         // how to undo, "" if it can't be undone
    val opened: Boolean = false,   // handed to another app (Messages, dialer, Clock); you finish there
    val actionId: Long? = null,    // the action history record, set once logged
) {
    companion object {
        fun error(msg: String) = ToolResult(false, "Error: $msg", cardLabel = "Could not do it", cardTitle = msg)
    }
}

class ToolArgsException(message: String) : Exception(message)

/** One thing the model can do on the phone. Each is a small Kotlin class (spec section 5). */
interface Tool {
    val spec: ToolSpec
    val name get() = spec.name
    /** Shown in Settings → Tools. */
    val label: String
    /** Always ask, or just do it (with undo); the owner can change it per tool. */
    val defaultPolicy: ToolPolicy
    /** Android runtime permissions this tool needs; asked the first time it runs. */
    val permissions: List<String> get() = emptyList()
    /** Reading tools never ask: they change nothing. */
    val readOnly: Boolean get() = false

    /** Turn the model's arguments into a preview, or throw [ToolArgsException] if they make no sense. */
    suspend fun preview(args: Map<String, String>): Preview

    /** Do it. [args] may have been edited on the confirmation sheet. */
    suspend fun run(args: Map<String, String>): ToolResult
}

class ToolRegistry(tools: List<Tool>) {
    val all: List<Tool> = tools
    private val byName = tools.associateBy { it.name }
    operator fun get(name: String): Tool? = byName[name]
    fun specsJson(): List<String> = all.map { it.spec.toJson() }

    /** Whether to show the confirmation sheet before running. Actions suggested after reading web text always ask. */
    fun mustAsk(tool: Tool, policies: Map<String, ToolPolicy>, afterWebText: Boolean): Boolean {
        if (tool.readOnly) return false
        if (afterWebText) return true
        return (policies[tool.name] ?: tool.defaultPolicy) == ToolPolicy.Ask
    }
}
