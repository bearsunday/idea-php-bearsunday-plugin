package idea.bear.sunday.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import idea.bear.sunday.mcp.facts.AlpsFactsService
import kotlin.coroutines.coroutineContext

/**
 * BEAR.Sunday MCP tools. Every tool is read-only and answers with a JSON envelope carrying a
 * status and the provenance of the answer.
 */
class BearSundayMcpToolset : McpToolset {

    @McpTool
    @McpDescription("Spike tool: returns the current project name to verify BEAR.Sunday MCP toolset registration.")
    suspend fun bear_spike_ping(): String {
        val project = coroutineContext.project
        return "BEAR.Sunday MCP spike OK: project=" + project.name
    }

    @McpTool
    @McpDescription(
        "Read-only. Returns a normalized ALPS profile (title, doc, links and the full descriptor tree) as JSON. " +
            "profilePath is absolute or project-relative; when omitted the single profile in the project is used, " +
            "and a project with several profiles answers with status=ambiguous plus the candidate paths."
    )
    suspend fun bear_alps_profile_read(profilePath: String? = null): String =
        facts().profileRead(profilePath)

    @McpTool
    @McpDescription(
        "Read-only. Finds one ALPS descriptor definition by id (or by href such as \"#User\") anywhere in the " +
            "descriptor tree and returns it as JSON. Searches every profile in the project unless profilePath is given."
    )
    suspend fun bear_alps_descriptor_lookup(id: String? = null, href: String? = null, profilePath: String? = null): String =
        facts().descriptorLookup(id, href, profilePath)

    @McpTool
    @McpDescription(
        "Read-only. Lists ALPS state transitions (safe, unsafe, idempotent descriptors) as JSON, optionally " +
            "filtered by from (the id of the containing descriptor), rel, or rt (\"#User\" and \"User\" are both " +
            "accepted). Each transition carries the matching BEAR.Resource #[Link] / #[Embed] declarations under " +
            "\"implementations\"; those are omitted while the project index is still building."
    )
    suspend fun bear_alps_transition_lookup(
        from: String? = null,
        rel: String? = null,
        rt: String? = null,
        profilePath: String? = null
    ): String = facts().transitionLookup(from, rel, rt, profilePath)

    @McpTool
    @McpDescription(
        "Read-only. Resolves the links of an ALPS profile and of its descriptors as JSON, reporting for each href " +
            "whether it points inside the profile, at another file, or at an external URL, and whether the target " +
            "exists. Optionally filtered by rel."
    )
    suspend fun bear_alps_links_resolve(profilePath: String? = null, rel: String? = null): String =
        facts().linksResolve(profilePath, rel)

    private suspend fun facts(): AlpsFactsService = AlpsFactsService.getInstance(coroutineContext.project)
}
