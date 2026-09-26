package io.julienmetral.tasks.identity.services;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IdenticonGeneratorTest {

    private static final Pattern BACKGROUND = Pattern.compile("<rect [^>]*fill=\"(hsl\\([^\"]+\\))\"");

    private final IdenticonGenerator generator = new IdenticonGenerator();

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    private String backgroundOf(UUID id) {
        Matcher matcher = BACKGROUND.matcher(generator.svg(id));

        assertThat(matcher.find()).isTrue();

        return matcher.group(1);
    }

    @Test
    void svgIsWellFormedXmlWithAnSvgRootInTheSvgNamespace() throws Exception {
        Document document = parse(generator.svg(UUID.randomUUID()));
        Element root = document.getDocumentElement();

        assertThat(root.getLocalName()).isEqualTo("svg");
        assertThat(root.getNamespaceURI()).isEqualTo("http://www.w3.org/2000/svg");
        assertThat(root.getAttribute("viewBox")).isEqualTo("0 0 128 128");
        assertThat(root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "rect").getLength()).isOne();
    }

    @Test
    void sameIdAlwaysGivesTheSameImage() {
        UUID id = UUID.randomUUID();

        assertThat(generator.svg(id)).isEqualTo(generator.svg(id));
        assertThat(new IdenticonGenerator().svg(id)).isEqualTo(generator.svg(id));
    }

    @Test
    void differentIdsUsuallyGetDifferentColours() {
        Set<String> colours = new HashSet<>();

        IntStream.range(0, 100).forEach(i -> colours.add(backgroundOf(UUID.randomUUID())));

        // 360 hues and 25 saturations: 100 random ids sharing a handful of colours would mean a broken derivation
        assertThat(colours).hasSizeGreaterThan(80);
    }

    @Test
    void consecutiveUuidV7IdsGetDifferentColours() {
        UUID first = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
        UUID next = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c");

        assertThat(backgroundOf(first)).isNotEqualTo(backgroundOf(next));
    }

    @Test
    void imageDoesNotContainTheId() {
        UUID id = UUID.randomUUID();
        String svg = generator.svg(id);

        assertThat(svg)
                .doesNotContain(id.toString())
                .doesNotContain(id.toString().replace("-", ""))
                .doesNotContainIgnoringCase("<text")
                .doesNotContainIgnoringCase("<title");
    }

    @Test
    void hueStaysInRangeAndSaturationStaysMuted() {
        Pattern hsl = Pattern.compile("hsl\\((\\d+),(\\d+)%,52%\\)");

        IntStream.range(0, 200).forEach(i -> {
            Matcher matcher = hsl.matcher(backgroundOf(UUID.randomUUID()));

            assertThat(matcher.matches()).isTrue();
            assertThat(Integer.parseInt(matcher.group(1))).isBetween(0, 359);
            assertThat(Integer.parseInt(matcher.group(2))).isBetween(45, 69);
        });
    }
}
