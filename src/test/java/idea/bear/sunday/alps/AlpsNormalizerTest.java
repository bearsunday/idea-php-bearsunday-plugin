package idea.bear.sunday.alps;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlpsNormalizerTest {

    @Test
    void rejectsDescriptorsNestedBeyondTheDepthLimit() {
        StringBuilder nested = new StringBuilder("{\"id\": \"d\"");
        for (int i = 0; i < 70; i++) {
            nested.insert(nested.length(), ", \"descriptor\": [{\"id\": \"d" + i + "\"");
        }
        String json = "{\"alps\": {\"descriptor\": [" + nested + "}]".repeat(70) + "}]}}";

        AlpsParseException exception =
            assertThrows(AlpsParseException.class, () -> AlpsNormalizer.fromJson(json, "alps.json"));
        assertTrue(exception.getMessage().contains("nested deeper"), exception.getMessage());
    }

    @Test
    void trimsJsonDocsLikeXmlDocs() {
        AlpsProfile profile = AlpsNormalizer.fromJson("""
            {"alps": {"doc": {"value": "  padded  "}, "descriptor": [{"id": "A", "doc": "   "}]}}
            """, "alps.json");

        assertEquals("padded", profile.doc());
        assertNull(profile.descriptors().get(0).doc());
    }

    @Test
    void readsTopLevelDescriptorsOfJsonProfile() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");

        assertEquals(
            List.of("Index", "About", "Blog", "BlogPosting", "about"),
            profile.descriptors().stream().map(AlpsDescriptor::id).filter(Objects::nonNull).toList()
        );
        assertEquals(6, profile.descriptors().size());
        assertEquals("/project/alps.json", profile.sourcePath());
        assertFalse(profile.xml());
    }

    @Test
    void foldsDocObjectValueIntoString() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");

        assertEquals("Blog entry list page", descriptor(profile, "Blog").doc());
    }

    @Test
    void acceptsPlainStringDoc() {
        AlpsProfile profile = AlpsNormalizer.fromJson("""
            {"alps": {"doc": "Profile doc", "descriptor": [{"id": "User", "doc": "A user"}]}}
            """, "/project/alps.json");

        assertEquals("Profile doc", profile.doc());
        assertEquals("A user", profile.descriptors().get(0).doc());
    }

    @Test
    void acceptsSingleDescriptorObject() {
        AlpsProfile profile = AlpsNormalizer.fromJson("""
            {"alps": {"descriptor": {"id": "User", "descriptor": {"id": "name"}}}}
            """, "/project/alps.json");

        assertEquals(1, profile.descriptors().size());
        assertEquals("User", profile.descriptors().get(0).id());
        assertEquals(List.of("name"), profile.descriptors().get(0).children().stream().map(AlpsDescriptor::id).toList());
    }

    @Test
    void acceptsSingleLinkObject() {
        AlpsProfile profile = AlpsNormalizer.fromJson("""
            {"alps": {"link": {"rel": "self", "href": "https://example.com/alps.json"}}}
            """, "/project/alps.json");

        assertEquals(List.of(new AlpsLink("self", "https://example.com/alps.json")), profile.links());
    }

    @Test
    void defaultsDefinitionTypeToSemantic() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");

        assertEquals("semantic", descriptor(profile, "Index").type());
    }

    @Test
    void keepsHrefOnlyDescriptorAsReference() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");
        AlpsDescriptor reference = profile.descriptors().get(4);

        assertTrue(reference.isReference());
        assertEquals("Foo.json#Foo", reference.href());
        assertNull(reference.type());
        assertEquals(-1, reference.textOffset());
    }

    @Test
    void nestsChildDescriptors() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");
        AlpsDescriptor blog = descriptor(profile, "Blog").children().get(1);

        assertEquals("blogPosting", blog.id());
        assertEquals("safe", blog.type());
        assertEquals("#BlogPosting", blog.rt());
        assertEquals("item", blog.rel());
        assertTrue(blog.isTransition());
        assertEquals(List.of("#id"), blog.children().stream().map(AlpsDescriptor::href).toList());
    }

    @Test
    void readsTransitionReturnType() {
        AlpsProfile profile = AlpsNormalizer.fromJson(fixture("alps.json"), "/project/alps.json");

        assertEquals("#Blog", descriptor(profile, "Index").children().get(0).rt());
    }

    @Test
    void recordsBestEffortTextOffsetOfDefinition() {
        String raw = fixture("alps.json");
        AlpsProfile profile = AlpsNormalizer.fromJson(raw, "/project/alps.json");
        int offset = descriptor(profile, "Blog").textOffset();

        assertTrue(offset >= 0);
        assertTrue(raw.startsWith("\"id\": \"Blog\"", offset));
    }

    /**
     * The fixture holds a descriptor whose id is "id". Searching for the bare value found the
     * key name of the very first descriptor in the file, so the offset pointed at another
     * descriptor entirely.
     */
    @Test
    void anchorsTheOffsetToTheIdMemberWhenTheIdCollidesWithAKeyName() {
        String raw = fixture("alps.json");
        AlpsProfile profile = AlpsNormalizer.fromJson(raw, "/project/alps.json");
        int offset = descriptor(profile, "id").textOffset();

        assertTrue(offset >= 0);
        assertTrue(raw.startsWith("\"id\": \"id\"", offset), () -> raw.substring(offset, offset + 20));
    }

    @Test
    void readsXmlProfile() {
        AlpsProfile profile = AlpsNormalizer.fromXml(fixture("alps.title.xml"), "/project/alps.title.xml");
        AlpsDescriptor home = descriptor(profile, "Home");

        assertTrue(profile.xml());
        assertEquals(5, profile.descriptors().size());
        assertEquals("Home", home.title());
        assertEquals("semantic", home.type());
        assertEquals(List.of("#goState"), home.children().stream().map(AlpsDescriptor::href).toList());
        assertTrue(home.children().get(0).isReference());
        assertEquals("#State", descriptor(profile, "goState").rt());
        assertEquals("go state", descriptor(profile, "goState").title());
    }

    @Test
    void normalizesXmlAndJsonToTheSameModel() {
        AlpsProfile fromJson = AlpsNormalizer.fromJson("""
            {"alps": {
              "title": "Demo",
              "doc": {"value": "Demo profile"},
              "link": [{"rel": "self", "href": "https://example.com/alps.json"}],
              "descriptor": [
                {"id": "User", "doc": {"value": "A user"}, "descriptor": [{"href": "#name"}]},
                {"id": "goUser", "type": "safe", "rt": "#User", "rel": "item"}
              ]
            }}
            """, "/project/alps.json");
        AlpsProfile fromXml = AlpsNormalizer.fromXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <alps>
                <title>Demo</title>
                <doc>Demo profile</doc>
                <link rel="self" href="https://example.com/alps.json"/>
                <descriptor id="User">
                    <doc>A user</doc>
                    <descriptor href="#name"/>
                </descriptor>
                <descriptor id="goUser" type="safe" rt="#User" rel="item"/>
            </alps>
            """, "/project/alps.xml");

        assertEquals(fromJson.title(), fromXml.title());
        assertEquals(fromJson.doc(), fromXml.doc());
        assertEquals(fromJson.links(), fromXml.links());
        assertEquals(withoutOffsets(fromJson.descriptors()), withoutOffsets(fromXml.descriptors()));
    }

    @Test
    void rejectsXmlWithDoctype() {
        AlpsParseException exception = assertThrows(AlpsParseException.class, () -> AlpsNormalizer.fromXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE alps [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <alps><descriptor id="User"/></alps>
            """, "/project/alps.xml"));

        assertNotNull(exception.getMessage());
    }

    @Test
    void rejectsXmlWithoutAlpsRoot() {
        assertThrows(AlpsParseException.class, () -> AlpsNormalizer.fromXml("<profile/>", "/project/alps.xml"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(AlpsParseException.class, () -> AlpsNormalizer.fromJson("{\"alps\": ", "/project/alps.json"));
    }

    @Test
    void rejectsJsonWithoutAlpsKey() {
        assertThrows(AlpsParseException.class, () -> AlpsNormalizer.fromJson("{\"descriptor\": []}", "/project/alps.json"));
    }

    private static AlpsDescriptor descriptor(AlpsProfile profile, String id) {
        AlpsDescriptor descriptor = AlpsLinkResolver.findById(profile.descriptors(), id);
        assertNotNull(descriptor, "descriptor not found: " + id);

        return descriptor;
    }

    private static List<AlpsDescriptor> withoutOffsets(List<AlpsDescriptor> descriptors) {
        List<AlpsDescriptor> stripped = new ArrayList<>();
        for (AlpsDescriptor descriptor : descriptors) {
            stripped.add(new AlpsDescriptor(
                descriptor.id(),
                descriptor.type(),
                descriptor.rt(),
                descriptor.href(),
                descriptor.rel(),
                descriptor.doc(),
                descriptor.def(),
                descriptor.tag(),
                descriptor.title(),
                descriptor.links(),
                withoutOffsets(descriptor.children()),
                -1
            ));
        }

        return stripped;
    }

    private static String fixture(String name) {
        try (InputStream stream = AlpsNormalizerTest.class.getResourceAsStream("/alps/" + name)) {
            assertNotNull(stream, "missing fixture: " + name);

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
