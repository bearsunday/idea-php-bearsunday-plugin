package idea.bear.sunday.mcp

import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.CoroutineContext

/**
 * Reads the project the MCP server bound to the current tool call.
 *
 * The accessor is a Kotlin top-level extension property, so it compiles into a facade class named
 * after the file that declares it, and the platform moved that declaration between releases:
 * 2025.2 puts it in `ProjectContextElementKt`, 2026.2 in `McpCallInfoKt`. The source form
 * (`coroutineContext.project`) is identical either way, but the compiled reference is not, so a
 * plugin built against one release throws `NoClassDefFoundError` on the other. Looking the
 * accessor up by name keeps one artifact working on both.
 */
internal object McpProjectContext {

    /** Newest facade first, so a current IDE resolves on the first attempt. */
    private val FACADE_CLASSES = listOf(
        "com.intellij.mcpserver.McpCallInfoKt",
        "com.intellij.mcpserver.ProjectContextElementKt"
    )

    private const val ACCESSOR = "getProject"

    private val accessor: Method by lazy { findAccessor(FACADE_CLASSES) }

    fun of(context: CoroutineContext): Project {
        // Method.invoke wraps whatever the accessor throws in an InvocationTargetException whose
        // own message is null, so the platform's typed "no project opened" error would surface to
        // the caller as an opaque reflection failure. Rethrowing the cause keeps the reflection
        // detour invisible.
        val project = try {
            accessor.invoke(null, context)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }

        return project as? Project
            ?: throw IllegalStateException("$ACCESSOR did not return a project for this MCP call")
    }

    fun findAccessor(classNames: List<String>): Method {
        val classLoader = McpProjectContext::class.java.classLoader
        for (className in classNames) {
            val method = runCatching {
                Class.forName(className, false, classLoader).getMethod(ACCESSOR, CoroutineContext::class.java)
            }.getOrNull()
            if (method != null) {
                return method
            }
        }

        throw IllegalStateException(
            "This IDE exposes no known MCP project accessor (looked for $ACCESSOR in $classNames). " +
                "The BEAR.Sunday MCP tools need an update for this release."
        )
    }
}
