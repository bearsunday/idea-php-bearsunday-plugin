package idea.bear.sunday.mcp;

import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP project accessor is looked up by name because the platform moves its Kotlin facade
 * between releases (2025.2 declares it in ProjectContextElementKt, 2026.2 in McpCallInfoKt).
 * These tests pin the names and the signature the lookup depends on, so a rename fails here
 * rather than at the first tool call.
 */
class McpProjectContextTest {

    private static final List<String> FACADES = List.of(
        "com.intellij.mcpserver.McpCallInfoKt",
        "com.intellij.mcpserver.ProjectContextElementKt"
    );

    @Test
    void findsTheAccessorOfTheIdeUnderTest() {
        Method accessor = McpProjectContext.INSTANCE.findAccessor(FACADES);

        assertEquals("getProject", accessor.getName());
        assertEquals("com.intellij.openapi.project.Project", accessor.getReturnType().getName());
        assertEquals(CoroutineContext.class, accessor.getParameterTypes()[0]);
        assertTrue(Modifier.isStatic(accessor.getModifiers()));
    }

    /**
     * Whatever the accessor itself throws must escape as itself: Method.invoke wraps it in an
     * InvocationTargetException whose own message is null, which the MCP layer would report as
     * an opaque internal error instead of the platform's typed "no project" message.
     *
     * <p>Compared against what the accessor produces rather than against a literal, because the
     * platform's own message is not this plugin's to pin: asserting only that the wrapper did not
     * escape would also pass for a detour that swallowed the typed failure and raised one of its
     * own.
     */
    @Test
    void doesNotLeakTheReflectionDetourWhenTheAccessorFails() {
        Throwable fromAccessor = accessorFailure();

        Throwable fromOf = assertThrows(
            Throwable.class,
            () -> McpProjectContext.INSTANCE.of(EmptyCoroutineContext.INSTANCE),
            "an empty context names no project, so the accessor is expected to fail"
        );

        assertEquals(fromAccessor.getClass(), fromOf.getClass(), () -> "escaped as " + fromOf);
        assertEquals(fromAccessor.getMessage(), fromOf.getMessage());
    }

    /** What the accessor throws for a context that names no project, reached without the detour. */
    private static Throwable accessorFailure() {
        Method accessor = McpProjectContext.INSTANCE.findAccessor(FACADES);
        InvocationTargetException wrapped = assertThrows(
            InvocationTargetException.class,
            () -> accessor.invoke(null, EmptyCoroutineContext.INSTANCE)
        );

        return wrapped.getTargetException();
    }

    @Test
    void failsWithAnActionableMessageWhenNoFacadeMatches() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> McpProjectContext.INSTANCE.findAccessor(List.of("does.not.Exist"))
        );

        assertTrue(exception.getMessage().contains("does.not.Exist"), exception.getMessage());
        assertTrue(exception.getMessage().contains("need an update"), exception.getMessage());
    }
}
