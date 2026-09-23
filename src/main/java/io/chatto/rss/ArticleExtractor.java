package io.chatto.rss;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Extracts readable paragraphs from a linked HTML page. */
final class ArticleExtractor {
    static String extract(String html) {
        var document = Jsoup.parse(html);
        document.select("script,style,nav,footer,header,aside,form,figure,figcaption,.advertisement,.related-content").remove();
        Element body = document.selectFirst("article .article-content");
        if (body == null) body = document.selectFirst("article");
        if (body == null) body = document.selectFirst("main");
        if (body == null) return "";
        var paragraphs = body.select(".article-details-text");
        if (paragraphs.isEmpty()) paragraphs = body.select("p");
        StringBuilder text = new StringBuilder();
        for (Element element : paragraphs) {
            if (element.parents().stream().anyMatch(parent -> parent.tagName().equals("p"))) continue;
            String paragraph = element.text().trim();
            if (paragraph.isEmpty()) continue;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(paragraph);
        }
        return text.toString();
    }
}
