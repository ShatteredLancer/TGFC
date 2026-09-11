package net.jejer.hipda.cookie;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * http://codereview.stackexchange.com/questions/61494/persistent-cookie-support-using-volley-and-httpurlconnection
 */
public class PersistentCookieStore implements CookieStore {
    private static final String COOKIE_PREFS = "CookiePrefsFile";
    private static final String COOKIE_NAME_PREFIX = "cookie_";
    private static final String COOKIE_DOMAIN = "tgfcer.com";
    private static final String BBS_HOST = "bbs.tgfcer.com";
    private static final String AUTH_COOKIE = "tgc_auth";
    private static final String SESSION_COOKIE = "tgc_sid";

    private final HashMap<String, ConcurrentHashMap<String, HttpCookie>> cookies;
    private final SharedPreferences cookiePrefs;

    /**
     * Construct a persistent cookie store.
     *
     * @param context Context to attach cookie store to
     */
    public PersistentCookieStore(Context context) {
        cookiePrefs = context.getSharedPreferences(COOKIE_PREFS, 0);
        cookies = new HashMap<>();

        // Load any previously stored cookies into the store
        Map<String, ?> prefsMap = cookiePrefs.getAll();
        for (Map.Entry<String, ?> entry : prefsMap.entrySet()) {
            if (!entry.getKey().startsWith(COOKIE_NAME_PREFIX) && entry.getValue() instanceof String) {
                String[] cookieNames = TextUtils.split((String) entry.getValue(), ",");
                for (String name : cookieNames) {
                    String encodedCookie = cookiePrefs.getString(COOKIE_NAME_PREFIX + name, null);
                    if (encodedCookie != null) {
                        HttpCookie decodedCookie = decodeCookie(encodedCookie);
                        if (decodedCookie != null && !decodedCookie.hasExpired()) {
                            String host = entry.getKey().toLowerCase(Locale.US);
                            if (isUntrustedAuthCookie(host, decodedCookie))
                                continue;
                            // A legacy cross-domain SID may belong to either backend. Do not guess
                            // during upgrade; the shared auth cookie can establish a fresh SID on
                            // the next request to each host.
                            if (isLegacySharedSessionCookie(host, decodedCookie))
                                continue;
                            normalizeCookieScope(host, decodedCookie);
                            if (!cookies.containsKey(entry.getKey()))
                                cookies.put(entry.getKey(), new ConcurrentHashMap<String, HttpCookie>());

                            String token = getCookieToken(host, decodedCookie);
                            cookies.get(entry.getKey()).put(token, decodedCookie);
                        }
                    }
                }

            }
        }
    }

    @Override
    public synchronized void add(URI uri, HttpCookie cookie) {
        if (uri == null || cookie == null || TextUtils.isEmpty(uri.getHost()))
            return;

        String host = uri.getHost().toLowerCase(Locale.US);

        // BBS is the only login authority used by the app. The water-area host has a separate
        // session backend but declares its cookies for .tgfcer.com; accepting an auth deletion or
        // replacement from that host would log the user out of BBS as well.
        if (isUntrustedAuthCookie(host, cookie))
            return;

        // bbs.tgfcer.com and s.tgfcer.com issue different SID values while both incorrectly mark
        // them as cross-subdomain cookies. Keep each SID host-only so visiting one backend cannot
        // replace the other backend's live session.
        normalizeCookieScope(host, cookie);
        String name = getCookieToken(host, cookie);

        // Remove the exact cookie being replaced. For the BBS auth cookie, also remove legacy
        // host-only copies left by earlier app versions so only one tgc_auth value is sent.
        for (ConcurrentHashMap<String, HttpCookie> hostCookies : cookies.values()) {
            for (Map.Entry<String, HttpCookie> entry : new ArrayList<>(hostCookies.entrySet())) {
                HttpCookie existing = entry.getValue();
                boolean sameNameAndPath = existing != null
                        && cookie.getName().equals(existing.getName())
                        && normalizedPath(cookie).equals(normalizedPath(existing));
                boolean legacyAuthCopy = AUTH_COOKIE.equals(cookie.getName())
                        && BBS_HOST.equals(host) && sameNameAndPath;
                if (name.equals(entry.getKey()) || legacyAuthCopy)
                    hostCookies.remove(entry.getKey());
            }
        }

        if (!cookie.hasExpired()) {
            if (!cookies.containsKey(host))
                cookies.put(host, new ConcurrentHashMap<String, HttpCookie>());
            cookies.get(host).put(name, cookie);
        }

        // Save cookie into persistent store
        if (isTgfcerHost(host)) {
            SharedPreferences.Editor prefsWriter = cookiePrefs.edit();
            for (Map.Entry<String, ConcurrentHashMap<String, HttpCookie>> entry : cookies.entrySet()) {
                if (!isTgfcerHost(entry.getKey()))
                    continue;
                if (entry.getValue().isEmpty())
                    prefsWriter.remove(entry.getKey());
                else
                    prefsWriter.putString(entry.getKey(), TextUtils.join(",", entry.getValue().keySet()));
            }
            if (cookie.hasExpired())
                prefsWriter.remove(COOKIE_NAME_PREFIX + name);
            else
                prefsWriter.putString(COOKIE_NAME_PREFIX + name, encodeCookie(new HttpCookieParcelable(cookie)));
            prefsWriter.apply();
        }
    }

    protected String getCookieToken(URI uri, HttpCookie cookie) {
        return getCookieToken(uri.getHost(), cookie);
    }

    private String getCookieToken(String host, HttpCookie cookie) {
        String domain = cookie.getDomain();
        String scope = TextUtils.isEmpty(domain) ? host : domain;
        String path = TextUtils.isEmpty(cookie.getPath()) ? "/" : cookie.getPath();
        return cookie.getName() + "|" + scope.toLowerCase(Locale.US) + "|" + path;
    }

    @Override
    public synchronized List<HttpCookie> get(URI uri) {
        ArrayList<HttpCookie> ret = new ArrayList<>();
        Set<String> addedCookies = new HashSet<>();
        String requestHost = uri.getHost();
        String requestPath = TextUtils.isEmpty(uri.getPath()) ? "/" : uri.getPath();
        for (Map.Entry<String, ConcurrentHashMap<String, HttpCookie>> entry : cookies.entrySet()) {
            for (HttpCookie cookie : entry.getValue().values()) {
                if (cookie.hasExpired())
                    continue;
                if (cookie.getSecure() && !"https".equalsIgnoreCase(uri.getScheme()))
                    continue;
                if (!pathMatches(cookie.getPath(), requestPath))
                    continue;
                String domain = cookie.getDomain();
                if (TextUtils.isEmpty(domain)) {
                    if (entry.getKey().equalsIgnoreCase(requestHost)
                            && addedCookies.add(getCookieToken(entry.getKey(), cookie)))
                        ret.add(cookie);
                } else if (HttpCookie.domainMatches(domain, requestHost)
                        && addedCookies.add(getCookieToken(entry.getKey(), cookie))) {
                    ret.add(cookie);
                }
            }
        }
        return ret;
    }

    @Override
    public synchronized boolean removeAll() {
        SharedPreferences.Editor prefsWriter = cookiePrefs.edit();
        prefsWriter.clear();
        prefsWriter.commit();
        cookies.clear();
        return true;
    }


    @Override
    public synchronized boolean remove(URI uri, HttpCookie cookie) {
        if (uri == null || cookie == null || TextUtils.isEmpty(uri.getHost()))
            return false;
        String host = uri.getHost().toLowerCase(Locale.US);
        if (isUntrustedAuthCookie(host, cookie))
            return false;
        normalizeCookieScope(host, cookie);
        String name = getCookieToken(host, cookie);
        boolean removed = false;
        for (ConcurrentHashMap<String, HttpCookie> hostCookies : cookies.values())
            removed |= hostCookies.remove(name) != null;

        if (removed) {
            SharedPreferences.Editor prefsWriter = cookiePrefs.edit();
            prefsWriter.remove(COOKIE_NAME_PREFIX + name);
            for (Map.Entry<String, ConcurrentHashMap<String, HttpCookie>> entry : cookies.entrySet()) {
                if (!isTgfcerHost(entry.getKey()))
                    continue;
                if (entry.getValue().isEmpty())
                    prefsWriter.remove(entry.getKey());
                else
                    prefsWriter.putString(entry.getKey(), TextUtils.join(",", entry.getValue().keySet()));
            }
            prefsWriter.apply();
            return true;
        }
        return false;
    }

    @Override
    public synchronized List<HttpCookie> getCookies() {
        ArrayList<HttpCookie> ret = new ArrayList<>();
        Set<String> addedCookies = new HashSet<>();
        for (String key : cookies.keySet())
            for (HttpCookie cookie : cookies.get(key).values())
                if (!cookie.hasExpired() && addedCookies.add(getCookieToken(key, cookie)))
                    ret.add(cookie);

        return ret;
    }

    @Override
    public synchronized List<URI> getURIs() {
        ArrayList<URI> ret = new ArrayList<>();
        for (String key : cookies.keySet())
            try {
                ret.add(new URI("https", key, "/", null));
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }

        return ret;
    }

    private boolean isTgfcerHost(String host) {
        return host != null && (host.equals(COOKIE_DOMAIN) || host.endsWith("." + COOKIE_DOMAIN));
    }

    private boolean isUntrustedAuthCookie(String host, HttpCookie cookie) {
        return isTgfcerHost(host) && !BBS_HOST.equals(host)
                && AUTH_COOKIE.equals(cookie.getName());
    }

    private boolean isLegacySharedSessionCookie(String host, HttpCookie cookie) {
        return isTgfcerHost(host) && SESSION_COOKIE.equals(cookie.getName())
                && !TextUtils.isEmpty(cookie.getDomain());
    }

    private void normalizeCookieScope(String host, HttpCookie cookie) {
        if (isTgfcerHost(host) && SESSION_COOKIE.equals(cookie.getName()))
            cookie.setDomain(null);
    }

    private boolean pathMatches(String cookiePath, String requestPath) {
        if (TextUtils.isEmpty(cookiePath) || "/".equals(cookiePath))
            return true;
        if (!requestPath.startsWith(cookiePath))
            return false;
        return requestPath.length() == cookiePath.length()
                || cookiePath.endsWith("/")
                || requestPath.charAt(cookiePath.length()) == '/';
    }

    private String normalizedPath(HttpCookie cookie) {
        return TextUtils.isEmpty(cookie.getPath()) ? "/" : cookie.getPath();
    }

    /**
     * Serializes Cookie object into String
     *
     * @param cookie cookie to be encoded, can be null
     * @return cookie encoded as String
     */
    protected String encodeCookie(HttpCookieParcelable cookie) {
        if (cookie == null)
            return null;

        return byteArrayToHexString(ParcelableUtil.marshall(cookie));
    }

    /**
     * Returns cookie decoded from cookie string
     *
     * @param cookieString string of cookie as returned from http request
     * @return decoded cookie or null if exception occured
     */
    protected HttpCookie decodeCookie(String cookieString) {
        byte[] bytes = hexStringToByteArray(cookieString);
        HttpCookieParcelable cookieParcel = ParcelableUtil.unmarshall(bytes, HttpCookieParcelable.CREATOR);
        return cookieParcel.getCookie();
    }

    /**
     * Using some super basic byte array &lt;-&gt; hex conversions so we don't have to rely on any
     * large Base64 libraries. Can be overridden if you like!
     *
     * @param bytes byte array to be converted
     * @return string containing hex values
     */
    protected String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte element : bytes) {
            int v = element & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString().toUpperCase(Locale.US);
    }

    /**
     * Converts hex values from strings to byte array
     *
     * @param hexString string of hex-encoded values
     * @return decoded byte array
     */
    protected byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }
}
