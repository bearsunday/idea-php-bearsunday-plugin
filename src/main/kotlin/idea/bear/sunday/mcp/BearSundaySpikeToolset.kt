package idea.bear.sunday.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlin.coroutines.coroutineContext

/**
 * M0c spike: verifies that a BEAR.Sunday toolset can be registered on the
 * com.intellij.mcpServer.mcpToolset extension point and receive the current
 * project through the coroutine context.
 */
class BearSundaySpikeToolset : McpToolset {

    @McpTool
    @McpDescription("Spike tool: returns the current project name to verify BEAR.Sunday MCP toolset registration.")
    suspend fun bear_spike_ping(): String {
        val project = coroutineContext.project
        return "BEAR.Sunday MCP spike OK: project=" + project.name
    }
}
