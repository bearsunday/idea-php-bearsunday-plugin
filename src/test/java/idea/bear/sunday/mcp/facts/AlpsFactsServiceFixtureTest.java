package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlpsFactsServiceFixtureTest {

    private static final String PROFILE = """
        {
          "alps": {
            "title": "Demo",
            "doc": {"value": "Demo profile"},
            "link": [
              {"rel": "self", "href": "#User"},
              {"rel": "profile", "href": "missing.json"},
              {"rel": "help", "href": "https://example.com/help"}
            ],
            "descriptor": [
              {
                "id": "User",
                "descriptor": [
                  {"id": "name", "type": "semantic"}
                ]
              },
              {"id": "goUser", "type": "safe", "rt": "#User", "rel": "item"}
            ]
          }
        }
        """;

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
    void readsTheSingleProfileOfTheProject() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().profileRead(null));
        JsonObject profile = envelope.getAsJsonObject("profile");

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("Demo", profile.get("title").getAsString());
        assertEquals("Demo profile", profile.get("doc").getAsString());
        assertEquals(2, profile.getAsJsonArray("descriptors").size());
        assertEquals(1, profile.getAsJsonArray("descriptors").get(0).getAsJsonObject().getAsJsonArray("descriptors").size());
        assertEquals("saved", envelope.getAsJsonObject("provenance").get("fresh").getAsString());
        assertEquals("alps.json", envelope.getAsJsonObject("provenance").get("path").getAsString());
    }

    @Test
    void readsTheProfileGivenByProjectRelativePath() {
        addPhysicalFile("api/demo.alps.json", PROFILE);

        JsonObject envelope = envelope(facts().profileRead("api/demo.alps.json"));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("Demo", envelope.getAsJsonObject("profile").get("title").getAsString());
    }

    @Test
    void refusesAnAbsolutePathOutsideTheProject(@TempDir Path outside) throws IOException {
        Path profile = outside.resolve("alps.json");
        Files.writeString(profile, PROFILE, StandardCharsets.UTF_8);
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(profile));

        JsonObject envelope = envelope(facts().profileRead(profile.toString()));

        assertEquals("not_found", envelope.get("status").getAsString());
    }

    @Test
    void reportsNotFoundWhenTheProjectHasNoProfile() {
        JsonObject envelope = envelope(facts().profileRead(null));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertNotNull(envelope.get("error"));
    }

    @Test
    void reportsAmbiguousWhenTheProjectHasSeveralProfiles() {
        addPhysicalFile("alps.json", PROFILE);
        addPhysicalFile("api/other.alps.json", PROFILE);

        JsonObject envelope = envelope(facts().profileRead(null));

        assertEquals("ambiguous", envelope.get("status").getAsString());
        assertEquals(2, envelope.getAsJsonArray("candidates").size());
    }

    @Test
    void reportsParseErrorForBrokenProfile() {
        addPhysicalFile("alps.json", "{\"alps\": ");

        JsonObject envelope = envelope(facts().profileRead(null));

        assertEquals("parse_error", envelope.get("status").getAsString());
    }

    @Test
    void looksUpNestedDescriptorById() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().descriptorLookup("name", null, null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("name", envelope.getAsJsonObject("descriptor").get("id").getAsString());
        assertEquals("semantic", envelope.getAsJsonObject("descriptor").get("type").getAsString());
    }

    @Test
    void looksUpDescriptorByHrefFragment() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().descriptorLookup(null, "#User", null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("User", envelope.getAsJsonObject("descriptor").get("id").getAsString());
    }

    @Test
    void reportsNotFoundForUnknownDescriptor() {
        addPhysicalFile("alps.json", PROFILE);

        assertEquals("not_found", envelope(facts().descriptorLookup("Nope", null, null)).get("status").getAsString());
    }

    @Test
    void filtersTransitionsByReturnType() {
        addPhysicalFile("alps.json", PROFILE);

        JsonArray transitions = envelope(facts().transitionLookup(null, null, "User", null)).getAsJsonArray("transitions");

        assertEquals(1, transitions.size());
        assertEquals("goUser", transitions.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("#User", transitions.get(0).getAsJsonObject().get("rt").getAsString());
    }

    /** A padded filter names the same descriptor as an unpadded one. */
    @Test
    void ignoresSurroundingSpaceInTheTransitionFilters() {
        addPhysicalFile("alps.json", PROFILE);

        assertEquals(1, envelope(facts().transitionLookup(null, null, " User ", null)).getAsJsonArray("transitions").size());
        assertEquals(1, envelope(facts().transitionLookup(null, " item ", null, null)).getAsJsonArray("transitions").size());
    }

    @Test
    void acceptsReturnTypeWithLeadingHash() {
        addPhysicalFile("alps.json", PROFILE);

        assertEquals(1, envelope(facts().transitionLookup(null, null, "#User", null)).getAsJsonArray("transitions").size());
    }

    @Test
    void returnsNoTransitionForUnknownReturnType() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().transitionLookup(null, null, "Missing", null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(0, envelope.getAsJsonArray("transitions").size());
    }

    /** Malformed text and an unread file are different things to tell a caller. */
    @Test
    void reportsAMalformedProfileAsAParseError() {
        addPhysicalFile("alps.json", "{ not json");

        assertEquals("parse_error", envelope(facts().profileRead(null)).get("status").getAsString());
    }

    @Test
    void resolvesProfileLinks() {
        addPhysicalFile("alps.json", PROFILE);

        JsonArray links = envelope(facts().linksResolve(null, null)).getAsJsonArray("links");
        JsonObject fragment = link(links, "self");
        JsonObject relative = link(links, "profile");
        JsonObject external = link(links, "help");

        assertEquals(3, links.size());
        assertTrue(fragment.get("exists").getAsBoolean());
        assertFalse(fragment.get("external").getAsBoolean());
        assertEquals("alps.json#User", fragment.get("resolvedPath").getAsString());
        assertEquals("profile", relative.get("owner").getAsString());
        assertFalse(relative.get("exists").getAsBoolean());
        assertFalse(relative.get("external").getAsBoolean());
        assertTrue(external.get("external").getAsBoolean());
        assertFalse(external.get("exists").getAsBoolean());
    }

    @Test
    void resolvesRelativeLinkToExistingFile() {
        addPhysicalFile("alps.json", """
            {"alps": {"link": [{"rel": "profile", "href": "other.json"}], "descriptor": []}}
            """);
        addPhysicalFile("other.json", "{}");

        JsonObject resolved = link(envelope(facts().linksResolve(null, null)).getAsJsonArray("links"), "profile");

        assertTrue(resolved.get("exists").getAsBoolean());
        assertEquals("other.json", resolved.get("resolvedPath").getAsString());
    }

    /** Only a fragment or a sibling file resolves locally; other href forms must not pretend to. */
    @Test
    void doesNotResolveARootAbsoluteOrNonHttpHrefAgainstTheProfileDirectory() {
        addPhysicalFile("schema/user.json", "{}");
        addPhysicalFile("alps.json", """
            {
              "alps": {
                "link": [
                  {"rel": "describedby", "href": "/schema/user.json"},
                  {"rel": "canonical", "href": "urn:example:user"},
                  {"rel": "help", "href": "//example.com/help"}
                ],
                "descriptor": [{"id": "User"}]
              }
            }
            """);

        JsonArray links = envelope(facts().linksResolve(null, null)).getAsJsonArray("links");
        JsonObject rootAbsolute = link(links, "describedby");
        JsonObject urn = link(links, "canonical");
        JsonObject protocolRelative = link(links, "help");

        // A root-absolute href is relative to a site root only the deployment knows: resolving
        // it against the profile directory finds a file the href does not name.
        assertFalse(rootAbsolute.get("exists").getAsBoolean(), rootAbsolute::toString);
        assertFalse(rootAbsolute.has("resolvedPath"), rootAbsolute::toString);
        assertTrue(urn.get("external").getAsBoolean(), urn::toString);
        assertTrue(protocolRelative.get("external").getAsBoolean(), protocolRelative::toString);
    }

    @Test
    void filtersLinksByRel() {
        addPhysicalFile("alps.json", PROFILE);

        JsonArray links = envelope(facts().linksResolve(null, "help")).getAsJsonArray("links");

        assertEquals(1, links.size());
        assertEquals("help", links.get(0).getAsJsonObject().get("rel").getAsString());
    }

    private static JsonObject link(JsonArray links, String rel) {
        for (JsonElement element : links) {
            if (rel.equals(element.getAsJsonObject().get("rel").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("link not found: " + rel);
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private AlpsFactsService facts() {
        return AlpsFactsService.getInstance(fixture.getProject());
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
