package itkach.aard2.dictionary.dsl;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link DslHtmlRenderer}, rendering cards parsed by
 * {@link DslParser#parseCard(String)}.
 */
public class DslHtmlRendererTest {

    private static final String HAVE_CARD = "{to }have\n"
            + "\t[m1][b]1)[/b] [trn]avoir[/trn] [p]v.[/p][/m]\n"
            + "\t[m2][ex]I ~ a cat, ^~ fun[/ex][/m]\n"
            + "\t[m1]see <<possess>>, [ref dict=\"Other\"]own it[/ref][/m]\n"
            + "\t@ ~ fun\n"
            + "\ts'amuser\n"
            + "\t@\n"
            + "\t[s]have.wav[/s] [s]cat.png[/s] [s]clip.avi[/s]\n";

    private static String haveHtml;

    @BeforeClass
    public static void renderHaveCard() {
        Map<String, String> abbreviations = new HashMap<>();
        abbreviations.put("v.", "verb");
        haveHtml = DslHtmlRenderer.render(DslParser.parseCard(HAVE_CARD), abbreviations);
    }

    @Test
    public void headwordShowsUnsortedPartWithoutBraces() {
        assertTrue(haveHtml, haveHtml.contains("<h3 class=\"dsl-hw\">to have</h3>"));
    }

    @Test
    public void marginTagsIndentLines() {
        assertTrue(haveHtml, haveHtml.contains("<div class=\"dsl-line\" style=\"margin-left:1em\"><b>1)</b>"));
        assertTrue(haveHtml, haveHtml.contains("<div class=\"dsl-line\" style=\"margin-left:2em\">"));
    }

    @Test
    public void zoneTagsBecomeClassedSpans() {
        assertTrue(haveHtml, haveHtml.contains("<span class=\"dsl-trn\">avoir</span>"));
        assertTrue(haveHtml, haveHtml.contains("<span class=\"dsl-ex\">"));
    }

    @Test
    public void abbreviationGetsTooltip() {
        assertTrue(haveHtml, haveHtml.contains("<abbr class=\"dsl-p\" title=\"verb\">v.</abbr>"));
    }

    @Test
    public void tildeIsReplacedByHeadword() {
        assertTrue(haveHtml, haveHtml.contains("I have a cat, Have fun"));
    }

    @Test
    public void cardLinksAreRelative() {
        assertTrue(haveHtml, haveHtml.contains("<a class=\"dsl-ref\" href=\"possess\">possess</a>"));
        assertTrue(haveHtml, haveHtml.contains("<a class=\"dsl-ref\" href=\"own%20it\">own it</a>"));
    }

    @Test
    public void subentryIsRenderedInline() {
        assertTrue(haveHtml, haveHtml.contains(
                "<div class=\"dsl-sub\"><div class=\"dsl-sub-hw\">have fun</div>"
                        + "<div class=\"dsl-line\">s'amuser</div></div>"));
    }

    @Test
    public void mediaBecomesSoundImageAndLink() {
        assertTrue(haveHtml, haveHtml.contains("<a class=\"dsl-sound\" href=\"have.wav\" "
                + "onclick=\"$SLOB.playAudio(this.href); return false;\">"));
        assertTrue(haveHtml, haveHtml.contains("<img class=\"dsl-img\" src=\"cat.png\" alt=\"cat.png\">"));
        assertTrue(haveHtml, haveHtml.contains("<a class=\"dsl-media\" href=\"clip.avi\">clip.avi</a>"));
    }

    @Test
    public void colorsUrlsAndEscapes() {
        String html = render("word\n\t[c red]rouge[/c] [c]vert[/c] [url]https://example.org/?a=1&b=2[/url]"
                + " \\[x\\] a < b & c ~\n");
        assertTrue(html, html.contains("<span style=\"color:red\">rouge</span>"));
        assertTrue(html, html.contains("<span style=\"color:green\">vert</span>"));
        assertTrue(html, html.contains("<a class=\"dsl-url\" href=\"https://example.org/?a=1&amp;b=2\">"
                + "https://example.org/?a=1&amp;b=2</a>"));
        assertTrue(html, html.contains("[x] a &lt; b &amp; c word"));
    }

    @Test
    public void unsafeInputIsNeutralised() {
        String html = render("word\n\t[c red;background:url(x)]t[/c] [url]javascript:alert(1)[/url] <script>\n");
        assertFalse(html, html.contains("background"));
        assertFalse(html, html.contains("href=\"javascript"));
        assertFalse(html, html.contains("<script>"));
    }

    @Test
    public void commentsAndUnknownTagsAreDropped() {
        String html = render("word\n\t{{hidden}}shown [foo]kept[/foo] [lang name=\"French\"]mot[/lang]\n");
        assertFalse(html, html.contains("hidden"));
        assertTrue(html, html.contains("shown kept <span class=\"dsl-lang\">mot</span>"));
    }

    private static String render(String card) {
        return DslHtmlRenderer.render(DslParser.parseCard(card), Collections.emptyMap());
    }
}
