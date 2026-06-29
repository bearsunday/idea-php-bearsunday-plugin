package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QueryCompletionFixtureTest {

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder =
            factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void completesGetQueryKeysFromOnGetParameters() {
        addWeekdayResource();
        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/weekday', [<caret>]);
            """);

        Set<String> names = namesOf(keys);
        assertTrue(names.contains("year"));
        assertTrue(names.contains("month"));
        assertTrue(names.contains("day"));
        assertEquals(3, names.size());
        assertTrue(keys.stream().allMatch(k -> "onGet".equals(k.method())));
    }

    @Test
    void completesPostQueryKeysFromOnPostParameters() {
        fixture.addFileToProject("src/Resource/App/Article.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            class Article
            {
                public function onPost(string $title, string $body): void
                {
                }
            }
            """);

        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->post('app://self/article', [<caret>]);
            """);

        Set<String> names = namesOf(keys);
        assertTrue(names.contains("title"));
        assertTrue(names.contains("body"));
        assertTrue(keys.stream().allMatch(k -> "onPost".equals(k.method())));
    }

    @Test
    void completesForUriTwoArgForm() {
        addWeekdayResource();
        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->uri('app://self/weekday', [<caret>]);
            """);

        assertTrue(namesOf(keys).containsAll(Set.of("year", "month", "day")));
        assertTrue(keys.stream().allMatch(k -> "onGet".equals(k.method())));
    }

    @Test
    void completesForWithQueryChainForm() {
        addWeekdayResource();
        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->get->uri('app://self/weekday')->withQuery([<caret>]);
            """);

        assertTrue(namesOf(keys).containsAll(Set.of("year", "month", "day")));
        assertTrue(keys.stream().allMatch(k -> "onGet".equals(k.method())));
    }

    @Test
    void excludesAlreadyUsedKeys() {
        addWeekdayResource();
        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/weekday', ['year' => 2000, <caret>]);
            """);

        Set<String> names = namesOf(keys);
        assertFalse(names.contains("year"));
        assertTrue(names.contains("month"));
        assertTrue(names.contains("day"));
    }

    @Test
    void returnsNothingWhenUriDoesNotResolve() {
        addWeekdayResource();
        List<QueryCompletionProvider.QueryKey> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/missing', [<caret>]);
            """);

        assertTrue(keys.isEmpty());
    }

    private void addWeekdayResource() {
        fixture.addFileToProject("src/Resource/App/Weekday.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            class Weekday
            {
                public function onGet(int $year, int $month = 1, int $day = 1): string
                {
                    return '';
                }
            }
            """);
    }

    private List<QueryCompletionProvider.QueryKey> keysAtCaret(String caller) {
        fixture.configureByText("Caller.php", caller);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<List<QueryCompletionProvider.QueryKey>>) () -> {
                PsiElement element = fixture.getFile().findElementAt(fixture.getCaretOffset());
                assertNotNull(element);
                return QueryCompletionProvider.queryKeys(element, null);
            }
        );
    }

    private static Set<String> namesOf(List<QueryCompletionProvider.QueryKey> keys) {
        return keys.stream().map(QueryCompletionProvider.QueryKey::name).collect(Collectors.toSet());
    }
}