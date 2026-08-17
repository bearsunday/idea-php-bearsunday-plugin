package idea.bear.sunday.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.Project
import idea.bear.sunday.mcp.facts.AlpsFactsService
import idea.bear.sunday.mcp.facts.ApiDocFactsService
import idea.bear.sunday.mcp.facts.BodyShapeFactsService
import idea.bear.sunday.mcp.facts.ContractCompareService
import idea.bear.sunday.mcp.facts.DiBindingLookupService
import idea.bear.sunday.mcp.facts.DiModuleTreeService
import idea.bear.sunday.mcp.facts.LinkSuggestService
import idea.bear.sunday.mcp.facts.ResourceAttributeIndexService
import idea.bear.sunday.mcp.facts.ResourceFactsService
import idea.bear.sunday.mcp.facts.SchemaFactsService
import kotlin.coroutines.coroutineContext

/**
 * BEAR.Sunday MCP tools. Every tool is read-only and answers with a JSON envelope carrying a
 * status and the provenance of the answer.
 */
class BearSundayMcpToolset : McpToolset {

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
        "Read-only. Lists the PHP attributes the resource classes under a resource root carry, as JSON, one " +
            "entry per class or on* method that carries at least one. Every attribute is resolved through the " +
            "file's use statements to the class it names, so this answers \"which resources does this attribute " +
            "really apply to\" where a text search cannot: the same short name can alias different classes in " +
            "different files. attribute filters by class name (\"\\\\BEAR\\\\Resource\\\\Annotation\\\\JsonSchema\") " +
            "or by short name (\"JsonSchema\", matched against the last segment of the class name); method " +
            "filters by \"onGet\" or \"get\" and then reports method attributes only; resourceRoot is " +
            "project-relative and defaults to \"src/Resource\". Each attribute also carries the Ray.Aop " +
            "\"interceptors\" a module binds to it with annotatedWith() -- an empty list means no such binding " +
            "names it, not that no interceptor runs, because bindings made by another matcher are not indexed. " +
            "\"fqn\" is the class the attribute names under the file's use statements; whether that class " +
            "exists is not checked. \"scan\" reports how many files and classes were read, including " +
            "\"filesSkipped\" when a root was too large to read whole, so an empty result says how much " +
            "was looked at."
    )
    suspend fun bear_resource_attribute_index(
        attribute: String? = null,
        method: String? = null,
        resourceRoot: String? = null
    ): String = ResourceAttributeIndexService.getInstance(project()).index(attribute, method, resourceRoot)

    @McpTool
    @McpDescription(
        "Read-only. Finds the Ray.Di bindings a project's modules declare, as JSON: which implementation an " +
            "interface is bound to, and the module file and line that binds it. This is what a text search " +
            "cannot follow -- an injected \"#[Named('category')] SurrogateKeyInterface\" names neither the " +
            "implementation class nor anything the implementation contains, so grepping for either misses the " +
            "wiring. interfaceName filters by the class given to bind(), as a class name " +
            "(\"\\\\My\\\\SurrogateKeyInterface\") or a short name (\"SurrogateKeyInterface\", matched against " +
            "the last segment); qualifier filters by what annotatedWith() names, either a #[Named] string " +
            "(matched exactly) or a qualifier attribute class (by class or short name); moduleRoot is " +
            "project-relative and defaults to \"src\", so pass e.g. \"vendor/bear/package/src\" to read " +
            "framework bindings. Each binding carries \"boundBy\" -- the Ray.Di method that gave it its target " +
            "(\"to\", \"toProvider\", \"toConstructor\", \"toInstance\", \"toNull\", \"untargeted\" for a bind() " +
            "with no target, \"unknown\" for a chain continued somewhere this could not follow) -- and a " +
            "\"resolution\": \"static\" when the binding itself names the implementation class (bind()->to()), " +
            "otherwise \"dynamic-unresolved\", which means only that THIS TOOL does not name the implementation, " +
            "not that no implementation exists. Those are reported, never dropped, with the class their argument " +
            "names under \"targetClass\", or \"targetUnreadable\": true when the argument names a class this " +
            "could not read. A binding a filter could not be applied to, because the element being " +
            "filtered is the one whose value the source does not state (annotatedWith(\$this->qualifier), " +
            "annotatedWith(\"{\$this->prefix}_dsn\")), goes to \"unresolved\" rather than being silently " +
            "excluded, as do rename() calls, which move a binding to another interface or qualifier and are " +
            "reported rather than applied. Each \"unresolved\" entry says which of those it is under " +
            "\"reason\": \"interface-unreadable\", \"qualifier-unreadable\", \"chain-unreadable\" or " +
            "\"rename-not-applied\". \"scan\" reports how much was read, including \"filesSkipped\" when a " +
            "root was too large to read whole, so an empty answer says how far it looked. Limits: bindings are " +
            "collected project-wide, NOT resolved against a context string, so a binding an installed module " +
            "overrides is still listed; MultiBinder bindings and bindInterceptor()/bindPriorityInterceptor() " +
            "are not read at all."
    )
    suspend fun bear_di_binding_lookup(
        interfaceName: String? = null,
        qualifier: String? = null,
        moduleRoot: String? = null
    ): String = DiBindingLookupService.getInstance(project()).lookup(interfaceName, qualifier, moduleRoot)

    @McpTool
    @McpDescription(
        "Read-only. Resolves a BEAR.Sunday context string (\"prod-hal-api-app\") to the module tree it installs, " +
            "as JSON -- the wiring a context selects, which no single file states. Each hyphen-separated segment " +
            "names {AppName}\\Module\\{Segment}Module when the app declares one (\"origin\": \"app\") and " +
            "\\BEAR\\Package\\Context\\{Segment}Module otherwise (\"origin\": \"framework\"), exactly as " +
            "BEAR\\Package\\Module's class_exists fallback tries them; a segment neither names is listed under " +
            "\"unresolvedSegments\" with the candidate class names that were tried, so an absence says what was " +
            "looked for. " +
            "Under each segment come the modules it installs, walked recursively through \$this->install() and " +
            "\$this->override() -- read from the module class's own body AND from the module classes it extends, " +
            "because a module that leaves its wiring to a base module (\"final class ProdModule extends " +
            "AbstractProdModule {}\") states its installs there and nowhere else. An edge read from a base class " +
            "carries \"inheritedFrom\" naming it; that says the base installs it, not that this module certainly " +
            "runs it, because whether the subclass's configure() chains to the inherited one is not checked. A " +
            "node names the installed module class and \"filePath\", the file that class is " +
            "DECLARED in, while \"installedAt\" carries the file and line the install is WRITTEN at, which need not " +
            "be the same file. \"priority\" orders the segments by which one wins a conflict: the loader wraps them " +
            "right to left and Ray.Di's Container::merge keeps the receiving container's bindings, so priority 1 " +
            "-- the LEFTMOST segment -- beats the rest, and \"frameworkOverride\" (priority 0, the AppMetaModule " +
            "the loader overrides everything with) beats them all. \"assistedModule\" is the other end of the same " +
            "chain: Ray\\Di\\AssistedModule, the module the loader starts from and every segment wraps, so its " +
            "priority is one past the last segment's and its bindings are the weakest in the tree. Both are in " +
            "the answer whatever the context says, and marked \"classUnresolved\" when the package is not " +
            "installed rather than left out. No install()/override() call the walk reads is " +
            "ever dropped, however little of it could be read: an " +
            "install whose module the source does not name (\$this->install(\$module), a conditional install) " +
            "keeps its \"kind\" and is marked \"moduleUnreadable\": true with its source text; a call the module " +
            "makes to an install()/override() that it OR ONE OF ITS BASE CLASSES declares is marked " +
            "\"ownMethod\": true, meaning PHP dispatches the call to that method rather than to Ray.Di's; a module named by a class the index cannot resolve is " +
            "marked \"classUnresolved\": true and not walked into; a module whose own base class the index " +
            "cannot resolve is marked \"baseClassUnresolved\" with the name its extends clause states, so an " +
            "empty node is never mistaken for a module that installs nothing; a module reached twice is expanded once and " +
            "then marked \"visited\": true, which means its subtree is elsewhere in this answer; a walk that hits " +
            "the node cap marks the nodes it cut with \"skipped\": true and counts them in \"modulesSkipped\". " +
            "Every edge carries the \"text\" of the call it was read from. " +
            "\"appNamespaceUnknown\": true means src/Module/AppModule.php could not be read, so the app-side " +
            "candidate could not even be named and a segment reported as \"framework\" may really be shadowed by " +
            "an app module. Limits: this names the tree, it does not decide which binding wins -- ask " +
            "bear_di_binding_lookup for the bindings themselves. A class resolved by the naming convention is NOT " +
            "checked to be a Ray\\Di\\AbstractModule, which the loader additionally requires, so a segment " +
            "reported here can still be one BEAR rejects at boot with InvalidContextException. Wiring a module " +
            "picks up from a trait is not read. " +
            "Unlike bear_di_binding_lookup, this tool resolves class names through the project index, so it " +
            "answers status=index_not_ready while the index is building. Pass diagram=true to also get " +
            "\"diagram\": a Mermaid flowchart of the same tree, one box per module class and one arrow per " +
            "install (thick for override), carrying every mark the JSON carries, for showing the wiring to a " +
            "human. It is a rendering of this answer, never a second opinion about the project."
    )
    suspend fun bear_di_module_tree_read(context: String, diagram: Boolean = false): String =
        DiModuleTreeService.getInstance(project()).read(context, diagram)

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
        "Read-only. Returns the shape of the body a resource method assigns to \$this->body, inferred from " +
            "the resource class: \"rendered\" and \"formatted\" carry the Psalm type (e.g. \"array{id: int, " +
            "title: string}\"), \"fields\" the top-level keys with their types. A body built differently on " +
            "different paths is a union and answers with \"branches\" instead, one entry per branch. method " +
            "accepts \"get\" or \"onGet\" and defaults to \"get\". The shape is what the code builds, not what " +
            "the resource promises; a method with no statically readable body answers with status=not_found."
    )
    suspend fun bear_resource_body_shape(uri: String, method: String = "get"): String =
        BodyShapeFactsService.getInstance(project()).shape(uri, method)

    @McpTool
    @McpDescription(
        "Read-only. Puts the field names of a resource's response JSON Schema next to the ones its ALPS " +
            "descriptor names and the ones its code assigns to \$this->body, and reports the fields no other " +
            "side names (onlyInSchema / onlyInAlps / onlyInBody, present once at least two sides exist). The " +
            "comparison is presence-only: it never claims the sides agree on meaning. A side that does not " +
            "exist is reported with \"available\": false, which is an answer, not an error."
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

    private suspend fun facts(): AlpsFactsService = AlpsFactsService.getInstance(project())

    private suspend fun project(): Project = McpProjectContext.of(coroutineContext)
}
