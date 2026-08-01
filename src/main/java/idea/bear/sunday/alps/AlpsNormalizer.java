package idea.bear.sunday.alps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes ALPS profiles into {@link AlpsProfile}. Both representations allowed by the ALPS
 * specification are accepted: {@code doc} as a plain string or as {@code {"value": ...}}, and
 * {@code descriptor} / {@code link} as either a single object or an array.
 */
public final class AlpsNormalizer {

    private static final String DEFAULT_TYPE = "semantic";

    private AlpsNormalizer() {
    }

    public static AlpsProfile fromJson(String json, String sourcePath) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new AlpsParseException("Malformed ALPS JSON: " + exception.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new AlpsParseException("ALPS JSON root must be an object");
        }
        JsonElement alps = root.getAsJsonObject().get("alps");
        if (alps == null || !alps.isJsonObject()) {
            throw new AlpsParseException("ALPS JSON must have an \"alps\" root object");
        }
        JsonObject profile = alps.getAsJsonObject();

        return new AlpsProfile(
            readString(profile, "title"),
            readDoc(profile),
            readJsonLinks(profile),
            readJsonDescriptors(profile, json),
            sourcePath,
            false
        );
    }

    public static AlpsProfile fromXml(String xml, String sourcePath) {
        Element root = parseXml(xml).getDocumentElement();
        if (root == null || !"alps".equals(root.getTagName())) {
            throw new AlpsParseException("ALPS XML root element must be <alps>");
        }

        return new AlpsProfile(
            childText(root, "title"),
            childText(root, "doc"),
            readXmlLinks(root),
            readXmlDescriptors(root, xml),
            sourcePath,
            true
        );
    }

    private static List<AlpsDescriptor> readJsonDescriptors(JsonObject parent, String raw) {
        List<AlpsDescriptor> descriptors = new ArrayList<>();
        for (JsonObject object : readObjects(parent, "descriptor")) {
            descriptors.add(toJsonDescriptor(object, raw));
        }

        return List.copyOf(descriptors);
    }

    private static AlpsDescriptor toJsonDescriptor(JsonObject object, String raw) {
        String id = readString(object, "id");

        return new AlpsDescriptor(
            id,
            readType(readString(object, "type"), id),
            readString(object, "rt"),
            readString(object, "href"),
            readString(object, "rel"),
            readDoc(object),
            readString(object, "def"),
            readString(object, "tag"),
            readString(object, "title"),
            readJsonLinks(object),
            readJsonDescriptors(object, raw),
            id == null ? -1 : raw.indexOf('"' + id + '"')
        );
    }

    private static List<AlpsLink> readJsonLinks(JsonObject parent) {
        List<AlpsLink> links = new ArrayList<>();
        for (JsonObject object : readObjects(parent, "link")) {
            links.add(new AlpsLink(readString(object, "rel"), readString(object, "href")));
        }

        return List.copyOf(links);
    }

    private static List<JsonObject> readObjects(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<JsonObject> objects = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    objects.add(item.getAsJsonObject());
                }
            }
        } else if (element.isJsonObject()) {
            objects.add(element.getAsJsonObject());
        }

        return objects;
    }

    @Nullable
    private static String readDoc(JsonObject object) {
        JsonElement doc = object.get("doc");
        if (doc == null || doc.isJsonNull()) {
            return null;
        }
        if (doc.isJsonPrimitive()) {
            return doc.getAsString();
        }
        if (doc.isJsonObject()) {
            JsonElement value = doc.getAsJsonObject().get("value");

            return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        }

        return null;
    }

    @Nullable
    private static String readString(JsonObject object, String name) {
        JsonElement element = object.get(name);

        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static List<AlpsDescriptor> readXmlDescriptors(Element parent, String raw) {
        List<AlpsDescriptor> descriptors = new ArrayList<>();
        for (Element element : childElements(parent, "descriptor")) {
            descriptors.add(toXmlDescriptor(element, raw));
        }

        return List.copyOf(descriptors);
    }

    private static AlpsDescriptor toXmlDescriptor(Element element, String raw) {
        String id = attribute(element, "id");

        return new AlpsDescriptor(
            id,
            readType(attribute(element, "type"), id),
            attribute(element, "rt"),
            attribute(element, "href"),
            attribute(element, "rel"),
            childText(element, "doc"),
            attribute(element, "def"),
            attribute(element, "tag"),
            attribute(element, "title"),
            readXmlLinks(element),
            readXmlDescriptors(element, raw),
            id == null ? -1 : raw.indexOf("id=\"" + id + '"')
        );
    }

    private static List<AlpsLink> readXmlLinks(Element parent) {
        List<AlpsLink> links = new ArrayList<>();
        for (Element element : childElements(parent, "link")) {
            links.add(new AlpsLink(attribute(element, "rel"), attribute(element, "href")));
        }

        return List.copyOf(links);
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                elements.add((Element) child);
            }
        }

        return elements;
    }

    @Nullable
    private static String childText(Element parent, String tagName) {
        List<Element> elements = childElements(parent, tagName);
        if (elements.isEmpty()) {
            return null;
        }
        String text = elements.get(0).getTextContent();

        return text == null || text.isBlank() ? null : text.trim();
    }

    @Nullable
    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);

        return value.isEmpty() ? null : value;
    }

    /** Definitions default to {@code semantic}; href-only references keep no type of their own. */
    @Nullable
    private static String readType(@Nullable String type, @Nullable String id) {
        if (type != null) {
            return type;
        }

        return id == null ? null : DEFAULT_TYPE;
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening: a profile is untrusted project content, so no DOCTYPE and no
            // external entity resolution of any kind.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new AlpsParseException("Malformed ALPS XML: " + exception.getMessage());
        }
    }
}
