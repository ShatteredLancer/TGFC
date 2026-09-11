package net.jejer.hipda.utils;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the authenticated account marker from the forum's legacy page header. */
public final class ForumSessionUtils {

    private static final Pattern QUERY_UID = Pattern.compile("(?:[?&]uid=)([0-9]+)");
    private static final Pattern PATH_UID = Pattern.compile("space-uid-([0-9]+)");

    private ForumSessionUtils() {
    }

    public static Identity findIdentity(Document document) {
        if (document == null)
            return Identity.empty();

        Element user = document.select("a#my").first();
        String selector = "a#my";
        if (user == null) {
            user = document.select("#userinfo a[href*=uid=]").first();
            selector = "#userinfo uid link";
        }

        // Only use broad profile-link fallbacks when the page also has an authenticated logout
        // link. Anonymous forum pages contain many member links for moderators and post authors.
        Element logout = findLogoutLink(document);
        if (user == null && logout != null) {
            user = document.select("a[href*=space.php][href*=uid=]").first();
            selector = "profile uid link";
            if (user == null) {
                user = document.select("a[href*=space-uid-]").first();
                selector = "profile uid path";
            }
            if (user == null && logout.parent() != null) {
                user = logout.parent().select("a[href*=space.php]").first();
                selector = "logout header profile link";
            }
        }

        if (user == null)
            return Identity.empty();

        String username = user.text().trim();
        String uid = extractUid(user.attr("href"));
        if (isEmpty(username))
            return Identity.empty();
        return new Identity(username, uid, selector);
    }

    public static boolean hasLogoutLink(Document document) {
        return document != null && findLogoutLink(document) != null;
    }

    private static Element findLogoutLink(Document document) {
        return document.select("a[href*=logout]").first();
    }

    private static String extractUid(String href) {
        if (isEmpty(href))
            return "";
        Matcher matcher = QUERY_UID.matcher(href);
        if (matcher.find())
            return matcher.group(1);
        matcher = PATH_UID.matcher(href);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    public static final class Identity {
        public final String username;
        public final String uid;
        public final String selector;

        private Identity(String username, String uid, String selector) {
            this.username = username;
            this.uid = uid;
            this.selector = selector;
        }

        private static Identity empty() {
            return new Identity("", "", "none");
        }
    }
}
