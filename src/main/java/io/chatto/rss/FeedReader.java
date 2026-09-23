package io.chatto.rss;

import org.jsoup.Jsoup;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Parses RSS, RDF RSS, and Atom while isolating malformed entries. */
final class FeedReader {
    record Item(String id, String title, String link, String description, String published) {}

    static List<Item> parse(String xml) throws Exception { return parse(xml, null); }

    static List<Item> parse(String xml, String feedUrl) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        String type = local(root);
        if (!type.equals("rss") && !type.equals("RDF") && !type.equals("feed"))
            throw new IllegalArgumentException("Unsupported RSS or Atom feed");
        boolean atom = type.equals("feed");
        var items = new ArrayList<Item>();
        var nodes = root.getElementsByTagNameNS("*", atom ? "entry" : "item");
        for (int i = 0; i < nodes.getLength(); i++) {
            try {
                var entry = (Element) nodes.item(i);
                String link = atom ? atomLink(entry) : value(entry, "link");
                if (link.isBlank()) link = value(entry, "origLink");
                if (link.isBlank()) continue;
                if (feedUrl != null && !feedUrl.isBlank()) link = URI.create(feedUrl).resolve(link).toString();
                HttpSource.requireHttpUrl(link);
                String id = atom ? value(entry, "id") : value(entry, "guid");
                if (id.isBlank()) id = link;
                String body = value(entry, "encoded");
                if (body.isBlank()) body = value(entry, atom ? "summary" : "description");
                if (body.isBlank()) body = value(entry, "content");
                items.add(new Item(id, Jsoup.parse(value(entry, "title")).text(), link,
                        Jsoup.parse(body).text(), atom ? first(entry, "published", "updated") : first(entry, "pubDate", "date")));
            } catch (IllegalArgumentException ignored) {
                // A bad entry does not prevent the rest of the feed from being published.
            }
        }
        return items;
    }

    private static String atomLink(Element entry) {
        String fallback = "";
        for (Node node = entry.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && local(element).equals("link")) {
                String href = element.getAttribute("href").trim();
                if (href.isBlank()) continue;
                if ("alternate".equals(element.getAttribute("rel")) || element.getAttribute("rel").isBlank()) return href;
                if (fallback.isBlank()) fallback = href;
            }
        }
        return fallback;
    }

    private static String first(Element entry, String one, String two) {
        String value = value(entry, one);
        return value.isBlank() ? value(entry, two) : value;
    }

    private static String value(Element entry, String name) {
        for (Node node = entry.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && local(element).equals(name)) return element.getTextContent().trim();
        return "";
    }

    private static String local(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }
}
