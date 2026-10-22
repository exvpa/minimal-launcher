package com.frame.home;

import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headlines from Google News' public RSS feeds, for the country the TV is in
 * (from {@link Weather#country}, else the TV's region setting, else Romania).
 */
final class News {
    /** Google News topic ids (TOP is the front page) and their names in the settings panel. */
    static final String[] TOPICS = {"TOP", "WORLD", "NATION", "BUSINESS", "TECHNOLOGY", "ENTERTAINMENT", "SPORTS", "SCIENCE", "HEALTH"};
    static final String[] TOPIC_NAMES = {"Top stories", "World", "National", "Business", "Technology", "Entertainment", "Sports", "Science", "Health"};

    /** Main language per country; anything else gets English. */
    private static final Map<String, String> LANGUAGES = new HashMap<>();

    static {
        String[] pairs = {"RO:ro", "MD:ro", "DE:de", "AT:de", "CH:de", "FR:fr", "BE:fr", "IT:it", "ES:es", "MX:es",
                "AR:es", "PT:pt", "BR:pt", "NL:nl", "PL:pl", "HU:hu", "CZ:cs", "SK:sk", "BG:bg", "GR:el", "TR:tr",
                "UA:uk", "SE:sv", "NO:no", "DK:da", "FI:fi", "IL:he", "JP:ja", "KR:ko", "RS:sr", "HR:hr", "SI:sl"};
        for (String p : pairs) LANGUAGES.put(p.substring(0, 2), p.substring(3));
    }

    static final class Item {
        final String title, source, link;
        final long time;

        Item(String title, String source, String link, long time) {
            this.title = title;
            this.source = source;
            this.link = link;
            this.time = time;
        }
    }

    interface Callback {
        void onNews(List<Item> items);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    /** The country the feed is for: location, else the TV's region, else Romania. */
    static String country() {
        String c = Weather.country;
        if (c == null || c.length() != 2) c = Locale.getDefault().getCountry();
        return c == null || c.length() != 2 ? "RO" : c.toUpperCase(Locale.ROOT);
    }

    void fetch(final String topic, final Callback callback) {
        final String country = country();
        new Thread(() -> {
            List<Item> items = load(country, topic);
            // Not every country has a Google News edition: fall back to the US one
            if (items.isEmpty() && !country.equals("US")) items = load("US", topic);
            if (items.isEmpty()) return;
            final List<Item> result = items;
            main.post(() -> callback.onNews(result));
        }, "news").start();
    }

    private static List<Item> load(String country, String topic) {
        String lang = LANGUAGES.containsKey(country) ? LANGUAGES.get(country) : "en";
        String hl = lang.equals("en") || lang.equals("pt") || lang.equals("es") ? lang + "-" + country : lang;
        String base = topic.equals("TOP") ? "https://news.google.com/rss"
                : "https://news.google.com/rss/headlines/section/topic/" + topic;
        String url = base + "?hl=" + hl + "&gl=" + country + "&ceid=" + country + ":" + lang;
        try {
            return parse(Http.get(url));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<Item> parse(String xml) throws Exception {
        List<Item> items = new ArrayList<>();
        SimpleDateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        String title = null, source = null, date = null, link = null, tag = null;
        boolean inItem = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = p.getName();
                if (tag.equals("item")) {
                    inItem = true;
                    title = source = date = link = null;
                }
            } else if (ev == XmlPullParser.TEXT && inItem && tag != null) {
                String text = p.getText().trim();
                if (text.isEmpty()) continue;
                if (tag.equals("title")) title = text;
                else if (tag.equals("source")) source = text;
                else if (tag.equals("pubDate")) date = text;
                else if (tag.equals("link")) link = text;
            } else if (ev == XmlPullParser.END_TAG) {
                if (p.getName().equals("item") && title != null) {
                    // Titles come as "Headline - Source"
                    if (source != null && title.endsWith(" - " + source)) {
                        title = title.substring(0, title.length() - source.length() - 3);
                    }
                    long time = 0;
                    try {
                        if (date != null) time = rfc822.parse(date).getTime();
                    } catch (Exception ignored) {
                        // Unknown date: shown without age
                    }
                    items.add(new Item(title, source == null ? "" : source, link, time));
                    inItem = false;
                }
                tag = null;
            }
        }
        return items;
    }
}
