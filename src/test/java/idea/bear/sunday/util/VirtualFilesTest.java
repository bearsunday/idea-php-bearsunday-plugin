package idea.bear.sunday.util;

import com.intellij.openapi.util.SystemInfo;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The temp directory is on the real file system, so the links here are real links. */
class VirtualFilesTest {

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

    /** A link under the directory to a file outside it names that outside file. */
    @Test
    void aLinkIsJudgedByWhereItPoints() throws IOException {
        assumeTrue(SystemInfo.isUnix);
        Path root = Path.of(fixture.getTempDirFixture().getTempDirPath());
        Path schemas = Files.createDirectories(root.resolve("var/json_schema"));
        Path outside = Files.createDirectories(root.resolve("var/json_validate"));
        Files.writeString(outside.resolve("user.json"), "{}");
        Files.createSymbolicLink(schemas.resolve("shared"), outside);

        VirtualFile schemaDir = refresh(schemas);
        VirtualFile linked = refresh(schemas.resolve("shared/user.json"));

        assertFalse(VirtualFiles.isInside(schemaDir, linked));
        assertTrue(VirtualFiles.isInside(refresh(outside), linked));
    }

    /** A directory reached through a link still holds the files under it. */
    @Test
    void aDirectoryBehindALinkHoldsItsOwnFiles() throws IOException {
        assumeTrue(SystemInfo.isUnix);
        Path root = Path.of(fixture.getTempDirFixture().getTempDirPath());
        Path real = Files.createDirectories(root.resolve("var/schemas"));
        Files.writeString(real.resolve("user.json"), "{}");
        Files.createSymbolicLink(root.resolve("var/json_schema"), real);

        VirtualFile linkedDir = refresh(root.resolve("var/json_schema"));
        VirtualFile file = refresh(root.resolve("var/json_schema/user.json"));

        assertTrue(VirtualFiles.isInside(linkedDir, file));
    }

    /** A file whose place cannot be found is not placed inside. */
    @Test
    void aFileWhoseTargetTheVfsDoesNotHoldIsNotInside() throws IOException {
        assumeTrue(SystemInfo.isUnix);
        Path root = Path.of(fixture.getTempDirFixture().getTempDirPath());
        Path schemas = Files.createDirectories(root.resolve("var/json_schema"));
        Files.createSymbolicLink(schemas.resolve("user.json"), root.resolve("var/missing/user.json"));

        VirtualFile schemaDir = refresh(schemas);
        VirtualFile unplaceable = refresh(schemas.resolve("user.json"));

        assertNull(unplaceable.getCanonicalFile());
        assertFalse(VirtualFiles.isInside(schemaDir, unplaceable));
        assertFalse(VirtualFiles.isInside(unplaceable, unplaceable));
    }

    private static VirtualFile refresh(Path path) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file, path.toString());
        return file;
    }
}
