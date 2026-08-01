package idea.bear.sunday.alps;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AlpsProfileDetectorFixtureTest {

    private static final String EMPTY_PROFILE = "{\"alps\": {}}";

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void findsProfilesByNameAndSuffix() {
        addPhysicalFile("alps.json", EMPTY_PROFILE);
        addPhysicalFile("api/foo.alps.xml", "<alps/>");

        assertEquals(List.of("alps.json", "foo.alps.xml"), profileNames());
    }

    @Test
    void skipsVendorDirectory() {
        addPhysicalFile("alps.json", EMPTY_PROFILE);
        addPhysicalFile("vendor/alps.json", EMPTY_PROFILE);

        List<VirtualFile> profiles = detector().findProfiles();

        assertEquals(1, profiles.size());
        assertFalse(profiles.get(0).getPath().contains("/vendor/"));
    }

    @Test
    void ignoresUnrelatedJsonFiles() {
        addPhysicalFile("composer.json", "{}");
        addPhysicalFile("alps.json", EMPTY_PROFILE);

        assertEquals(List.of("alps.json"), profileNames());
    }

    @Test
    void rescansAfterProfileIsAdded() {
        addPhysicalFile("alps.json", EMPTY_PROFILE);
        assertEquals(List.of("alps.json"), profileNames());

        addPhysicalFile("api/foo.alps.json", EMPTY_PROFILE);

        assertEquals(List.of("alps.json", "foo.alps.json"), profileNames());
    }

    private List<String> profileNames() {
        return detector().findProfiles().stream().map(VirtualFile::getName).sorted().toList();
    }

    private AlpsProfileDetector detector() {
        return AlpsProfileDetector.getInstance(fixture.getProject());
    }

    private void addPhysicalFile(String relativePath, String contents) {
        try {
            String basePath = fixture.getProject().getBasePath();
            assertNotNull(basePath);
            Path path = Path.of(basePath, relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
