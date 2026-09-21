package idea.bear.sunday;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every icon path an action declares in a plugin descriptor must name a file the jar actually
 * ships.
 *
 * <p>The platform resolves such a path verbatim: {@code createImageDescriptorList} appends only the
 * {@code _dark} and {@code @2x} suffixes and never swaps the extension, so a descriptor still
 * pointing at {@code /icons/foo.png} after the artwork became {@code foo.svg} renders no icon at
 * all, silently and with nothing in the log.
 *
 * <p>Review alone does not catch that mismatch. Deleting the file and referencing it can happen in
 * different files, in different pull requests, and Git then merges both cleanly with no conflict to
 * look at.
 */
class PluginDescriptorIconTest {

    /** The descriptor every plugin starts from; optional ones are reached through {@code config-file}. */
    private static final String ROOT_DESCRIPTOR = "/META-INF/plugin.xml";

    @Test
    void everyDeclaredIconResolvesToAShippedFile() {
        List<String> missing = new ArrayList<>();
        int checked = 0;

        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(ROOT_DESCRIPTOR));
        while (!pending.isEmpty()) {
            String descriptor = pending.remove();
            if (!visited.add(descriptor)) {
                continue;
            }
            NodeList elements = parse(descriptor).getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);

                String configFile = element.getAttribute("config-file");
                if (!configFile.isEmpty()) {
                    pending.add("/META-INF/" + configFile);
                }

                String icon = element.getAttribute("icon");
                // An icon value that is not a file name is a code reference such as
                // `AllIcons.General.Add`, which this test has no way to resolve.
                if (!icon.endsWith(".svg") && !icon.endsWith(".png")) {
                    continue;
                }
                checked++;
                String path = icon.startsWith("/") ? icon : "/" + icon;
                if (PluginDescriptorIconTest.class.getResource(path) == null) {
                    missing.add(descriptor + " declares " + icon);
                }
            }
        }

        assertTrue(checked > 0, "no icon path found in any descriptor, so the scan itself is broken");
        assertEquals(List.of(), missing, "icon paths with no file behind them");
    }

    private static Document parse(String descriptor) {
        try (InputStream stream = PluginDescriptorIconTest.class.getResourceAsStream(descriptor)) {
            assertNotNull(stream, "descriptor is not on the classpath: " + descriptor);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Plugin descriptors carry no doctype; refusing one keeps this parser off the network.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(stream);
        } catch (Exception e) {
            throw new AssertionError("cannot parse " + descriptor, e);
        }
    }
}
