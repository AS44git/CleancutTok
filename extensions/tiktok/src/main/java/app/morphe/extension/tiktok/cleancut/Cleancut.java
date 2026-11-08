/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * CleancutTok: TikTok as an inbox and a profile, with no feed to fall into.
 *
 * <p>Four layers, each one enough on its own for what it covers, so a TikTok update that slips
 * past one still meets the next:
 * <ol>
 *   <li>The bottom bar holds Inbox and Profile only, and the app opens on Inbox
 *       ({@link #bottomTabs}, {@link #startTag}).</li>
 *   <li>The requests that fetch a feed (For You, Following, Friends, Explore, Nearby, LIVE,
 *       Shop, suggested accounts) never leave the phone ({@link #gate}).</li>
 *   <li>A video opened from anywhere but a chat or a profile plays alone: TikTok's own
 *       single-video loader replaces whatever list it would have swiped through
 *       ({@link #pinsVideoPage}).</li>
 *   <li>The Hushfeed settings this rests on are locked ({@link #lockSettings}): no switch, pause,
 *       backup or reset in the app moves them.</li>
 * </ol>
 *
 * <p>The lock is permanent by design. It lives in the build, not in a preference, so there is
 * nothing to turn off. The way out is uninstalling.
 */
public final class Cleancut {
    static final String FAMILY = "cleancut";

    private Cleancut() {
    }

    // ---------------------------------------------------------------------------------------
    // 4. The permanent lock
    // ---------------------------------------------------------------------------------------

    /** Called once from {@link Settings}'s static initializer, before any setting is read. */
    public static void lockSettings() {
        try {
            // The bottom bar and the start tab.
            Settings.BOTTOM_NAVIGATION.lockTo(true);
            Settings.BOTTOM_NAVIGATION_TABS.lockTo(BottomNavigationTabOptions.INBOX + ","
                    + BottomNavigationTabOptions.PROFILE);
            Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.lockTo(true);
            Settings.START_PAGE.lockTo("inbox");
            // Home is gone, but if TikTok ever shows it, its feed stays shut behind the panel
            // and its pager doesn't swipe, with For You as the only tab across the top.
            Settings.FEED_LOCK.lockTo(true);
            Settings.FEED_NAVIGATION.lockTo(true);
            Settings.FEED_NAVIGATION_TABS.lockTo("HOT");
            Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.lockTo(true);
            // Suggestions, LIVE and Shop wherever Hushfeed can reach them.
            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.lockTo(true);
            Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.lockTo(true);
            Settings.HIDE_INBOX_SHOP.lockTo(true);
            Settings.HIDE_LIVE.lockTo(true);
            Settings.HIDE_SHOP.lockTo(true);
            Settings.HIDE_LIVE_ENTRANCE.lockTo(true);
        } catch (Throwable failure) {
            Logger.printException(() -> "CleancutTok could not lock its settings", failure);
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1. Inbox and Profile only
    // ---------------------------------------------------------------------------------------

    /** TikTok's tags for the tabs the bar keeps, normalized the way Hushfeed's tab options read them. */
    private static final Set<String> KEPT_BOTTOM_TABS = new HashSet<>(Arrays.asList(
            BottomNavigationTabOptions.INBOX, BottomNavigationTabOptions.PROFILE));

    /**
     * The bottom bar's tabs, Inbox and Profile only. A model with neither is not the bar this
     * expects (a sign-in screen, a new layout), and is handed back as it came.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<?> bottomTabs(List<?> tabs) {
        if (tabs == null || tabs.isEmpty()) return tabs;
        try {
            CopyOnWriteArrayList kept = new CopyOnWriteArrayList();
            for (Object tab : tabs) {
                String key = BottomNavigationTabOptions.normalizeRuntimeTag(tag(tab));
                if (key != null && KEPT_BOTTOM_TABS.contains(key)) kept.add(tab);
            }
            if (kept.isEmpty()) return tabs;
            HookStatus.bound(FAMILY, "bottom bar " + kept.size() + " of " + tabs.size());
            return kept;
        } catch (Throwable failure) {
            Logger.printException(() -> "CleancutTok could not trim the bottom bar", failure);
            return tabs;
        }
    }

    /** TikTok's own tags, as its main activity switches tabs with them. */
    static final String INBOX_TAG = "NOTIFICATION";
    static final String PROFILE_TAG = "USER";
    private static final Set<String> FEED_TAGS = new HashSet<>(Arrays.asList(
            "HOME", "FRIENDS_TAB", "FRIENDS_FEED", "SHOP", "MALL", "PUBLISH", "Live", "LIVE"));

    /**
     * The tab the main activity opens on, from any start: the launcher, a notification or a
     * link. One that would land on a feed, or on a tab the bar no longer has, lands on Inbox, or
     * on Profile when no account is signed in (TikTok sends Inbox without one to its sign-in).
     */
    public static String startTag(String tag, boolean signedIn) {
        if (tag != null && !FEED_TAGS.contains(tag)) return tag;
        String target = signedIn ? INBOX_TAG : PROFILE_TAG;
        HookStatus.bound(FAMILY, "start " + tag + " -> " + target);
        return target;
    }

    private static String tag(Object tab) {
        Object value = Reflect.invoke(tab, "tag");
        if (!(value instanceof String)) value = Reflect.invoke(tab, "getTag");
        return value instanceof String ? (String) value : null;
    }

    // ---------------------------------------------------------------------------------------
    // 2. The network gate
    // ---------------------------------------------------------------------------------------

    /**
     * API paths that fetch a feed or a page of recommendations. A request whose path starts with
     * one of these fails before it is sent. Single videos (/aweme/v1/aweme/detail/,
     * /aweme/v1/multi/aweme/detail/), profiles, messages, comments and search keep working.
     */
    static final String[] BLOCKED_PATHS = {
            // For You, and its variants.
            "/aweme/v1/feed/",
            "/aweme/v2/feed",
            "/self_define/aweme/v2/feed",
            "/api/v1/recommend/video_in_stream/",
            "/tiktok/v1/rss/feed",
            "/tiktok/v1/kids/feed",
            "/now/feed/",
            // Following and Friends.
            "/aweme/v1/follow/feed/",
            "/aweme/v2/follow/feed/",
            "/aweme/v1/following/interest/feed/",
            "/aweme/v1/friend/feed/",
            "/tiktok/v1/friend/friend_feed",
            "/tiktok/v1/skylight/feed",
            "/tiktok/v2/repost/feed/",
            "/tiktok/v1/thought/feed",
            "/tiktok/v2/thought/feed",
            // Explore, Nearby, the horizontal and offline feeds, Tako's feed, mini dramas.
            "/tiktok/feed/",
            "/aweme/v1/nearby/feed/",
            "/aweme/v1/discover/search/",
            "/tiktok/md/mini_drama/feed",
            "/tiktok/v1/paid_content/mini_drama/feed",
            // Suggested accounts.
            "/aweme/v1/recommend/user/",
            "/aweme/v1/following/page/user/recommend/",
            "/aweme/v1/follow/feed/recommend/",
            // LIVE's feeds and recommended rooms (a LIVE opened from a profile or a chat has a
            // room id of its own and isn't fetched through these).
            "/webcast/feed/",
            "/webcast/room/recommend_live/",
            "/webcast/game_feed/",
            "/aweme/v1/topview/live/",
            // Shop's home page and its recommendations.
            "/api/v1/mall/homepage/",
            "/api/v1/shop_tab/",
            "/api/v1/shop/recommend/feed/",
    };

    private static volatile Field requestField;
    private static volatile Class<?> requestFieldOwner;
    private static volatile boolean warned;

    /**
     * Called as each attempt of an API call through TikTok's Retrofit fork starts
     * ({@code SsHttpCall.getResponseWithInterceptorChain}). A feed request fails here as a
     * network error, which TikTok already knows how to show.
     */
    public static void gate(Object call) throws IOException {
        String path;
        try {
            path = path(call);
        } catch (Throwable unreadable) {
            if (!warned) {
                warned = true;
                Logger.printException(() -> "CleancutTok could not read a request's path", unreadable);
            }
            return;
        }
        if (path != null && noted(path)) noteFavoritesRequest(call, path);
        if (path == null || !blocks(path)) return;
        HookStatus.bound(FAMILY, "network gate");
        Logger.printDebug(() -> "[CleancutTok] blocked " + path);
        throw new IOException("CleancutTok: feeds are switched off");
    }

    /** Whether a request path is one of the feeds. The query, if any, is ignored. */
    static boolean blocks(String path) {
        int query = path.indexOf('?');
        String bare = query >= 0 ? path.substring(0, query) : path;
        // Some requests carry the whole address in the path field.
        int scheme = bare.indexOf("://");
        if (scheme >= 0) {
            int slash = bare.indexOf('/', scheme + 3);
            bare = slash >= 0 ? bare.substring(slash) : "/";
        }
        for (String blocked : BLOCKED_PATHS) {
            if (bare.startsWith(blocked)) return true;
        }
        return false;
    }

    private static String path(Object call) throws ReflectiveOperationException {
        if (call == null) return null;
        Class<?> type = call.getClass();
        Field field = requestField;
        if (field == null || requestFieldOwner != type) {
            field = null;
            for (Class<?> c = type; c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("originalRequest");
                } catch (NoSuchFieldException notHere) {
                    // up the chain
                }
            }
            if (field == null) throw new NoSuchFieldException("originalRequest on " + type.getName());
            field.setAccessible(true);
            requestField = field;
            requestFieldOwner = type;
        }
        Object request = field.get(call);
        if (request == null) return null;
        String path = invokeString(request, "getPath");
        return path != null ? path : invokeString(request, "getUrl");
    }

    private static String invokeString(Object target, String getter) {
        try {
            Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value == null ? null : value.toString();
        } catch (Throwable missing) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. Video pages play one video
    // ---------------------------------------------------------------------------------------

    /**
     * The pages that keep their list: a chat (its swipe goes through the other videos sent in
     * that chat, and nothing else) and a profile (that person's own posts).
     */
    static final Set<String> SWIPEABLE_FROM = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "from_chat",
            "from_auto_message",
            "from_chat_user_video",
            "from_profile_self",
            "from_profile_other",
            "from_school_post"
    )));

    /**
     * Called at the start of TikTok's video page operator factory with the page's parameters.
     * True hands the page TikTok's own single-video operator, which loads the video the page
     * was opened on and nothing after it. A page with no video id to load keeps its own.
     */
    public static boolean pinsVideoPage(Object param) {
        try {
            if (param == null) return false;
            String aid = asString(Reflect.invoke(param, "getAid"));
            if (aid == null || aid.isEmpty()) return false;
            String from = asString(Reflect.invoke(param, "getFrom"));
            if (from != null && SWIPEABLE_FROM.contains(from)) return false;
            HookStatus.bound(FAMILY, "one video " + (from == null || from.isEmpty() ? "(no source)" : from));
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "CleancutTok could not read a video page's source", failure);
            return false;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Paging parameters only: nothing that names the account or the device. */
    private static final java.util.Set<String> PAGING_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "cursor", "max_cursor", "min_cursor", "count", "offset", "sort", "sort_type", "order",
            "item_type", "type", "collect_type", "need_pin"));

    private static void noteFavoritesRequest(Object call, String path) {
        try {
            Object request = requestField != null ? requestField.get(call) : null;
            String url = request != null ? invokeString(request, "getUrl") : null;
            StringBuilder b = new StringBuilder("request ").append(path);
            if (url != null && url.indexOf('?') >= 0) {
                for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    String key = eq > 0 ? pair.substring(0, eq) : pair;
                    if (PAGING_KEYS.contains(key)) b.append(' ').append(pair);
                }
            }
            favLog(b.toString());
        } catch (Throwable t) {
            favLog("request " + path + " (" + t + ")");
        }
    }

    /**
     * TikTok silences the app's logcat output, so the diagnostics go to a file the app owns:
     * Android/data/<package>/files/cleancut-favourites.txt (adb pull reads it).
     */
    static synchronized void favLog(String line) {
        try {
            android.content.Context context = app.morphe.extension.shared.Utils.getContext();
            if (context == null) return;
            java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "cleancut-favourites.txt"), true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()));
                w.write(' ');
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics: the favourite sounds and effects lists come back empty while their counts
    // say otherwise. Their replies are written to the notes file to see what the server sends.
    // ---------------------------------------------------------------------------------------

    static boolean noted(String path) {
        return path.contains("collect") || path.contains("favorite") || path.contains("sticker")
                || path.contains("effect") || path.contains("prop");
    }

    private static int repliesNoted;

    /** Every parsed reply (via Follow diagnostics' hook); sound and effect lists are written down. */
    public static void noteReply(Object request, Object response) {
        try {
            if (request == null || repliesNoted >= 12) return;
            String url = invokeString(request, "getUrl");
            if (url == null) url = invokeString(request, "getPath");
            if (url == null) return;
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            if (!(path.contains("music/collect") || path.contains("sticker") || path.contains("effect")
                    || path.contains("prop/") || path.contains("collection"))) return;
            if (path.contains("listcollection")) return;   // videos: already understood
            repliesNoted++;
            Object body = response;
            try {
                java.lang.reflect.Method m = response.getClass().getMethod("body");
                body = m.invoke(response);
            } catch (Throwable ignored) {
                // not an SsResponse
            }
            StringBuilder b = new StringBuilder("reply ").append(path).append(" -> ");
            dump(b, body, 0);
            String text = b.length() > 30000 ? b.substring(0, 30000) + " …" : b.toString();
            favLog(text);
        } catch (Throwable t) {
            favLog("reply: " + t);
        }
    }

    private static void dump(StringBuilder b, Object o, int depth) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof CharSequence) {
            String s = o.toString();
            b.append('"').append(s.length() > (depth == 0 ? 25000 : 160) ? s.substring(0, depth == 0 ? 25000 : 160) + "…" : s).append('"');
            return;
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o.getClass().isEnum()) {
            b.append(o);
            return;
        }
        if (depth >= 4) { b.append(o.getClass().getSimpleName()); return; }
        if (o instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) o;
            b.append('[').append(c.size()).append(": ");
            int n = 0;
            for (Object e : c) {
                if (n++ >= 3) { b.append(" …"); break; }
                dump(b, e, depth + 1);
                b.append(", ");
            }
            b.append(']');
            return;
        }
        if (o instanceof java.util.Map) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
            b.append('{');
            int n = 0;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ >= 40) { b.append(" …"); break; }
                b.append(e.getKey()).append('=');
                dump(b, e.getValue(), depth + 1);
                b.append(", ");
            }
            b.append('}');
            return;
        }
        if (o.getClass().getName().startsWith("com.google.gson") || o.getClass().getName().contains("Json")) {
            String s = o.toString();
            b.append(s.length() > 25000 ? s.substring(0, 25000) + "…" : s);
            return;
        }
        b.append(o.getClass().getName()).append('{');
        int fields = 0;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (fields++ >= 60) break;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    b.append(f.getName()).append('=');
                    dump(b, v, depth + 1);
                    b.append(", ");
                } catch (Throwable ignored) {
                    // skip
                }
            }
        }
        b.append('}');
    }
}
