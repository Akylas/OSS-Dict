package itkach.aard2.dictionary.dsl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts a DSL card to an HTML article.
 *
 * <p>Pure Java (no Android, no slob) so it can be unit tested on the JVM.</p>
 *
 * <p>Links to other cards ({@code [ref]}, {@code <<…>>}) become relative
 * {@code href}s, which the article view resolves as lookups. Media
 * ({@code [s]file[/s]}) become relative URLs served from the dictionary's
 * resource archive.</p>
 */
public final class DslHtmlRenderer {

    private static final Pattern COLOR = Pattern.compile("[#A-Za-z0-9]+");
    private static final String DEFAULT_COLOR = "green";
    private static final Pattern WEB_URL = Pattern.compile("(?i)(https?|mailto):");

    private static final String STYLE = "<style>"
            + ".dsl-hw{margin:0.4em 0}"
            + ".dsl-line{margin:0.15em 0}"
            + ".dsl-opt{opacity:0.75}"
            + ".dsl-ex,.dsl-com{opacity:0.8}"
            + ".dsl-ex{font-style:italic}"
            + ".dsl-p{font-style:italic;text-decoration:none;border-bottom:1px dotted}"
            + ".dsl-stress{text-decoration:underline}"
            + ".dsl-sub{margin-left:1em}"
            + ".dsl-sub-hw{font-weight:bold;margin-top:0.3em}"
            + ".dsl-sound{text-decoration:none}"
            + "</style>";

    private DslHtmlRenderer() {
    }

    /**
     * Renders a card as a complete HTML document.
     *
     * @param card          the parsed card
     * @param abbreviations abbreviation → full text, used for {@code [p]} tooltips
     */
    @NonNull
    public static String render(@NonNull DslParser.Card card, @NonNull Map<String, String> abbreviations) {
        String tilde = card.headwords.isEmpty() ? "" : DslParser.tildeText(card.headwords.get(0));
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">").append(STYLE).append("</head><body>");
        for (String headword : card.headwords) {
            html.append("<h3 class=\"dsl-hw\">");
            new InlineRenderer(html, tilde, abbreviations).render(headword);
            html.append("</h3>");
        }
        int margin = 0;
        boolean inSubentry = false;
        for (String line : card.body) {
            if (line.startsWith("@")) {
                if (inSubentry) html.append("</div>");
                String subHeadword = line.substring(1).trim();
                inSubentry = !subHeadword.isEmpty();
                if (inSubentry) {
                    html.append("<div class=\"dsl-sub\"><div class=\"dsl-sub-hw\">");
                    new InlineRenderer(html, tilde, abbreviations).render(subHeadword);
                    html.append("</div>");
                }
                continue;
            }
            int lineStart = html.length();
            InlineRenderer renderer = new InlineRenderer(html, tilde, abbreviations);
            renderer.margin = margin;
            renderer.render(line);
            int lineMargin = renderer.lineMargin >= 0 ? renderer.lineMargin : margin;
            margin = renderer.margin;
            String open = lineMargin > 0
                    ? "<div class=\"dsl-line\" style=\"margin-left:" + lineMargin + "em\">"
                    : "<div class=\"dsl-line\">";
            html.insert(lineStart, open);
            html.append("</div>");
        }
        if (inSubentry) html.append("</div>");
        html.append("</body></html>");
        return html.toString();
    }

    /** Renders one line of DSL markup into {@link #html}. */
    private static final class InlineRenderer {
        final StringBuilder html;
        final String tilde;
        final Map<String, String> abbreviations;

        /** Margin in effect after this line ({@code [mN]} … {@code [/m]}). */
        int margin;
        /** Margin set by an {@code [mN]} before any text on this line, or -1. */
        int lineMargin = -1;
        boolean hasText;

        /** Start of the pending captured element ({@code [ref]}, {@code [p]}, …) in {@link #html}. */
        int captureStart = -1;
        String captureTag;
        final StringBuilder captureText = new StringBuilder();

        InlineRenderer(@NonNull StringBuilder html, @NonNull String tilde,
                       @NonNull Map<String, String> abbreviations) {
            this.html = html;
            this.tilde = tilde;
            this.abbreviations = abbreviations;
        }

        void render(@NonNull String line) {
            int index = 0;
            while (index < line.length()) {
                char character = line.charAt(index);
                if (character == '\\' && index + 1 < line.length()) {
                    text(String.valueOf(line.charAt(index + 1)));
                    index += 2;
                } else if (character == '[') {
                    int close = line.indexOf(']', index);
                    if (close < 0) {
                        text("[");
                        index++;
                    } else {
                        tag(line.substring(index + 1, close).trim());
                        index = close + 1;
                    }
                } else if (character == '<' && line.startsWith("<<", index) && line.indexOf(">>", index + 2) > 0) {
                    int close = line.indexOf(">>", index + 2);
                    link(line.substring(index + 2, close));
                    index = close + 2;
                } else if (character == '^' && line.startsWith("^~", index)) {
                    text(swapFirstCase(tilde));
                    index += 2;
                } else if (character == '~') {
                    text(tilde);
                    index++;
                } else if (character == '{' || character == '}') {
                    // Braces only delimit the unsorted part; never displayed.
                    index++;
                } else {
                    text(String.valueOf(character));
                    index++;
                }
            }
            if (captureStart >= 0) finishCapture();
        }

        private void text(@NonNull String text) {
            if (text.isEmpty()) return;
            if (!text.trim().isEmpty()) hasText = true;
            if (captureStart >= 0) captureText.append(text);
            html.append(escape(text));
        }

        private void link(@NonNull String target) {
            String cleanTarget = target.trim();
            html.append("<a class=\"dsl-ref\" href=\"").append(escape(encodePath(cleanTarget))).append("\">")
                    .append(escape(cleanTarget)).append("</a>");
            hasText = true;
        }

        private void tag(@NonNull String tag) {
            if (tag.isEmpty()) return;
            boolean closing = tag.charAt(0) == '/';
            String body = closing ? tag.substring(1).trim() : tag;
            int space = body.indexOf(' ');
            String name = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
            String attributes = space < 0 ? "" : body.substring(space + 1).trim();

            if (name.length() == 2 && name.charAt(0) == 'm' && Character.isDigit(name.charAt(1))) {
                margin = name.charAt(1) - '0';
                if (!hasText && lineMargin < 0) lineMargin = margin;
                return;
            }
            switch (name) {
                case "m":
                    if (closing) margin = 0;
                    return;
                case "b":
                case "i":
                case "u":
                case "sub":
                case "sup":
                    html.append(closing ? "</" : "<").append(name).append('>');
                    return;
                case "c":
                    if (closing) {
                        html.append("</span>");
                    } else {
                        String color = COLOR.matcher(attributes).matches() ? attributes : DEFAULT_COLOR;
                        html.append("<span style=\"color:").append(color).append("\">");
                    }
                    return;
                case "*":
                    span(closing, "dsl-opt");
                    return;
                case "ex":
                    span(closing, "dsl-ex");
                    return;
                case "com":
                    span(closing, "dsl-com");
                    return;
                case "trn":
                case "!trs":
                case "lang":
                case "t":
                    span(closing, "dsl-" + (name.equals("!trs") ? "notrs" : name));
                    return;
                case "'":
                    span(closing, "dsl-stress");
                    return;
                case "ref":
                case "url":
                case "p":
                case "s":
                case "video":
                    if (closing) {
                        if (name.equals(captureTag)) finishCapture();
                    } else {
                        if (captureStart >= 0) finishCapture();
                        captureStart = html.length();
                        captureTag = name;
                        captureText.setLength(0);
                    }
                    return;
                default:
                    // Unknown tags are dropped; their content is kept.
            }
        }

        private void span(boolean closing, @NonNull String cssClass) {
            html.append(closing ? "</span>" : "<span class=\"" + cssClass + "\">");
        }

        /** Wraps or replaces the text captured since the opening tag. */
        private void finishCapture() {
            String captured = captureText.toString().trim();
            String tag = captureTag;
            int start = captureStart;
            captureStart = -1;
            captureTag = null;
            captureText.setLength(0);
            switch (tag) {
                case "ref":
                    html.insert(start, "<a class=\"dsl-ref\" href=\"" + escape(encodePath(captured)) + "\">");
                    html.append("</a>");
                    break;
                case "url":
                    // Only web links: a javascript: URL would run inside the article page.
                    if (WEB_URL.matcher(captured).lookingAt()) {
                        html.insert(start, "<a class=\"dsl-url\" href=\"" + escape(captured) + "\">");
                        html.append("</a>");
                    }
                    break;
                case "p":
                    String full = abbreviations.get(captured);
                    html.insert(start, full != null
                            ? "<abbr class=\"dsl-p\" title=\"" + escape(full) + "\">"
                            : "<abbr class=\"dsl-p\">");
                    html.append("</abbr>");
                    break;
                default:
                    html.setLength(start);
                    html.append(media(captured));
            }
        }
    }

    /** HTML for a {@code [s]} media reference, resolved relative to the article URL. */
    @NonNull
    static String media(@NonNull String fileName) {
        if (fileName.isEmpty()) return "";
        String source = escape(encodePath(fileName));
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bmp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".svg")) {
            return "<img class=\"dsl-img\" src=\"" + source + "\" alt=\"" + escape(fileName) + "\">";
        }
        if (lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".spx")) {
            return "<a class=\"dsl-sound\" href=\"" + source
                    + "\" onclick=\"$SLOB.playAudio(this.href); return false;\">&#128266;</a>";
        }
        return "<a class=\"dsl-media\" href=\"" + source + "\">" + escape(fileName) + "</a>";
    }

    /** Percent-encodes a single relative path segment. */
    @NonNull
    static String encodePath(@NonNull String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    static String escape(@Nullable String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                default:
                    out.append(character);
            }
        }
        return out.toString();
    }

    @NonNull
    private static String swapFirstCase(@NonNull String text) {
        if (text.isEmpty()) return text;
        int first = text.codePointAt(0);
        int swapped = Character.isUpperCase(first) ? Character.toLowerCase(first) : Character.toUpperCase(first);
        return new StringBuilder().appendCodePoint(swapped).append(text.substring(Character.charCount(first))).toString();
    }
}
