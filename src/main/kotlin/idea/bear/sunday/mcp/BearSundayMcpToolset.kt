package idea.bear.sunday.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import idea.bear.sunday.mcp.facts.AlpsFactsService
import idea.bear.sunday.mcp.facts.ApiDocFactsService
import idea.bear.sunday.mcp.facts.ContractCompareService
import idea.bear.sunday.mcp.facts.LinkSuggestService
import idea.bear.sunday.mcp.facts.ResourceFactsService
import idea.bear.sunday.mcp.facts.SchemaFactsService
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

    @McpTool
    @McpDescription(
        "Read-only. Describes the BEAR.Resource class behind a resource URI as JSON: its class FQN and file, " +
            "its on* methods with parameters and PHP attributes, the #[Link] / #[Embed] relations it declares " +
            "(relationsOut) and the ones pointing at it (relationsIn). uri accepts \"app://self/user\", " +
            "\"page://self/index\" or \"/user\". Relations are omitted with \"relationsUnavailable\": " +
            "\"index_not_ready\" while the project index is still building."
    )
    suspend fun bear_resource_describe(uri: String): String =
        ResourceFactsService.getInstance(project()).describe(uri)

    @McpTool
    @McpDescription(
        "Read-only. Finds the JSON Schema files of a resource as JSON, with their property names, required " +
            "list and full contents. Give resourceUri (optionally method, e.g. \"get\" or \"onGet\") to follow " +
            "the #[JsonSchema] attribute (source \"attribute\") or the var/json_schema naming convention " +
            "(source \"convention\"), or schemaFile to look a file up by name (source \"file\"). kind is " +
            "\"response\" (default) or \"request\", which reads the params schema. Files that exist but cannot " +
            "be parsed are reported with an error instead of properties."
    )
    suspend fun bear_schema_lookup(
        resourceUri: String? = null,
        method: String? = null,
        schemaFile: String? = null,
        kind: String = "response"
    ): String = SchemaFactsService.getInstance(project()).lookup(resourceUri, method, schemaFile, kind)

    @McpTool
    @McpDescription(
        "Read-only. Looks operations up in the OpenAPI document generated by bear/api-doc (the docDir of " +
            "apidoc.xml, or docs/openapi.json), optionally filtered by path (exact match), method or " +
            "operationId, and returns each match with its JSON Pointer and body. Without any filter it lists " +
            "every path, method and pointer without the bodies. Answers with status=engine_unavailable when " +
            "the document has not been generated."
    )
    suspend fun bear_apidoc_operation_lookup(
        path: String? = null,
        method: String? = null,
        operationId: String? = null
    ): String = ApiDocFactsService.getInstance(project()).operationLookup(path, method, operationId)

    @McpTool
    @McpDescription(
        "Read-only. Puts the field names of a resource's response JSON Schema next to the ones its ALPS " +
            "descriptor names, and reports which side has fields the other does not (onlyInSchema / " +
            "onlyInAlps). The comparison is presence-only: it never claims the two agree on meaning. A side " +
            "that does not exist is reported with \"available\": false, which is an answer, not an error."
    )
    suspend fun bear_contract_compare(uri: String, method: String = "get"): String =
        ContractCompareService.getInstance(project()).compare(uri, method)

    @McpTool
    @McpDescription(
        "Read-only. Suggests ALPS links the project's conventions imply but the profile does not declare: a " +
            "descriptor whose response JSON Schema exists (rel describedby) or whose OpenAPI operation exists " +
            "(rel related). Give descriptorId or resourceUri for one descriptor, or neither to check every " +
            "top-level descriptor. The result is an inference, not a fact about the profile; already declared " +
            "links are never suggested."
    )
    suspend fun bear_alps_links_suggest(descriptorId: String? = null, resourceUri: String? = null): String =
        LinkSuggestService.getInstance(project()).suggest(descriptorId, resourceUri)

    private suspend fun facts(): AlpsFactsService = AlpsFactsService.getInstance(coroutineContext.project)

    private suspend fun project(): Project = coroutineContext.project
}
