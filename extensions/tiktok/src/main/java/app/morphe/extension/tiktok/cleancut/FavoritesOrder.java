/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/**
 * Favourites > Videos, oldest first.
 *
 * <p>TikTok pages favourites newest first, by time: each request asks for the videos saved
 * before a cursor (the save time of the last video on the previous page), and the server has
 * no oldest-first order. So, once, every page is walked and the cursor each page starts at is
 * kept (about 160 numbers for 4,700 favourites). Page j of the oldest-first list is then the
 * walk's page M-1-j, fetched fresh from TikTok (so video links never go stale) and reversed.
 *
 * <p>Every profile tab's page goes through one method, {@code X.0AEo.LJII} in 47.6.3 (type 4 is
 * favourites; it returns a {@code FeedItemList}). The patch calls {@link #enter} at its start,
 * which for favourites fetches the right page itself and returns it reversed. What TikTok gets
 * back as the next cursor is a small page number (1, 2, 3...), never mistaken for a save time;
 * {@link #enter} turns it back into the real one.
 *
 * <p>New favourites only ever arrive at the top (the newest end), so each opening re-walks only
 * the top until it meets the kept pages: usually one request. A video unfavourited since just
 * leaves its page; a video that would show twice where two pages meet is shown once.
 */
public final class FavoritesOrder {
    private static final int FAVORITES = 4;
    /** Videos per page when walking and serving (TikTok's own Videos tab asks for 10-20). */
    private static final int PAGE = 30;
    /** Page numbers handed to TikTok as cursors; real cursors are save times (~1.7e9). */
    private static final long MAX_TOKEN = 1_000_000L;
    private static final String PREFS = "cleancut_favorites";

    private FavoritesOrder() {}

    // --- the switch ----------------------------------------------------------------------

    public static boolean oldestFirst() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("oldest_first", true);
    }

    public static void setOldestFirst(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("oldest_first", on).apply();
    }

    // --- the hooks ---------------------------------------------------------------------------

    private static volatile Method fetcher;

    /** Set while this thread walks or serves (its own calls go through untouched). */
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    /**
     * The videos on each served page, by page number, so a video at a page seam shows once:
     * page j drops only what page j-1 already showed. (Not a running set: TikTok can ask for
     * the first page twice at once, and the second must not come back empty.)
     */
    private static final Map<Integer, Set<String>> SERVED = new ConcurrentHashMap<>();

    /** The class holding the fetch method, so the walk can call it. */
    public static void fetcherClass(Class<?> type) {
        if (fetcher != null || type == null) return;
        for (Method m : type.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 20 && p[3] == int.class && p[4] == long.class && p[5] == long.class
                    && m.getReturnType().getName().endsWith(".FeedItemList")) {
                m.setAccessible(true);
                fetcher = m;
                return;
            }
        }
    }

    /**
     * At the start of every profile page fetch, with all of its arguments. For favourites with
     * oldest-first on, fetches the right page itself and returns it reversed (the method then
     * returns that); otherwise returns null and TikTok's own fetch runs. A failed fetch throws
     * what TikTok's would have, so the app shows its usual retry.
     */
    public static Object enter(boolean a0, String a1, String a2, int type, long minCursor, long cursor,
                               int count, String a7, int a8, int a9, Integer a10, Object a11, boolean a12,
                               String a13, boolean a14, String a15, String a16, int a17, boolean a18,
                               String a19) throws Throwable {
        if (type != FAVORITES || INSIDE.get() || fetcher == null) return null;
        FavoritesTabs.attachSoon();
        List<Long> pages;
        Object[] args = {a0, a1, a2, type, minCursor, cursor, count, a7, a8, a9, a10, a11, a12,
                a13, a14, a15, a16, a17, a18, a19};
        try {
            if (!oldestFirst()) return null;
            if (cursor < 0 || cursor >= MAX_TOKEN) return null;  // a real save time: not ours
            String owner = (a2 != null && !a2.isEmpty()) ? a2 : (a1 != null && !a1.isEmpty() ? a1 : "me");
            // Never waits: the walk (first time) or the top check (after) runs on its own thread,
            // and until the walk is done TikTok's own order shows.
            pages = cursor == 0 ? Index.opened(owner, args) : Index.kept(owner);
        } catch (Throwable t) {
            Cleancut.favLog("favourites order: " + t);
            return null;
        }
        if (pages == null || pages.isEmpty()) return null;       // no index yet: TikTok's own order
        int page = (int) cursor;
        if (page >= pages.size()) return null;
        // A stretch of unavailable videos comes back as an empty page: skip on to the next.
        Object result = null;
        List<?> items = null;
        for (int tries = 0; tries < 6 && page < pages.size(); tries++, page++) {
            result = Index.fetchOrThrow(args, pages.get(pages.size() - 1 - page));
            if (result == null || statusCode(result) != 0) break;
            items = (List<?>) invoke(result, "getItems");
            if (items != null && !items.isEmpty()) break;
        }
        if (result == null || items == null || items.isEmpty() || statusCode(result) != 0) {
            Cleancut.favLog("favourites page " + cursor + " came back empty");
            return cursor == 0 ? null : result;   // first page: TikTok's own list, not a blank
        }
        try {
            Set<String> before = cursor == 0 ? Collections.emptySet() : SERVED.get(page - 1);
            Set<String> ids = new HashSet<>();
            List<Object> out = new ArrayList<>();
            for (int i = items.size() - 1; i >= 0; i--) {
                Object aweme = items.get(i);
                String id = aweme == null ? null : (String) invoke(aweme, "getAid");
                if (id != null) ids.add(id);
                if (id == null || before == null || !before.contains(id)) out.add(aweme);
            }
            if (out.isEmpty()) {                            // never an empty page
                for (int i = items.size() - 1; i >= 0; i--) out.add(items.get(i));
            }
            if (cursor == 0) SERVED.clear();
            SERVED.put(page, ids);
            invoke(result, "setItems", List.class, out);
            int next = page + 1;
            boolean more = next < pages.size();
            invoke(result, "setHasMore", int.class, more ? 1 : 0);
            invoke(result, "setCursor", long.class, more ? (long) next : 0L);
            invoke(result, "setMaxCursor", long.class, more ? (long) next : 0L);
        } catch (Throwable t) {
            Cleancut.favLog("favourites order (page): " + t);
        }
        return result;
    }

    // --- the index ---------------------------------------------------------------------------

    /** The cursors each page starts at, newest page first (the first is 0), kept on the phone. */
    private static final class Index {
        private static volatile List<Long> pages;
        private static String pagesOwner;
        private static volatile boolean complete;

        private static volatile boolean busy;

        static synchronized List<Long> kept(String owner) {
            if (!busy) load(owner);
            return complete ? pages : null;
        }

        /**
         * Favourites opened: the kept pages as they stand, and in the background either the
         * walk (not done yet) or a check of the top for new favourites (shown next opening).
         */
        static List<Long> opened(String owner, Object[] args) {
            List<Long> now;
            boolean walk;
            synchronized (Index.class) {
                if (!busy) load(owner);
                now = complete ? pages : null;
                walk = !complete;
                if (busy) return now;
                busy = true;
            }
            Thread t = new Thread(() -> {
                // Not under the lock: the walk takes minutes and openings must never wait on it.
                // Only this thread changes the pages (busy), and they're served only once
                // complete; the top check swaps in a whole new list in one assignment.
                try {
                    if (walk) walkAll(owner, args);
                    else refreshTop(owner, args);
                } catch (Throwable e) {
                    Cleancut.favLog("favourites background: " + e);
                } finally {
                    busy = false;
                }
            }, "cleancut-favourites");
            t.setDaemon(true);
            t.start();
            return now;
        }

        private static void load(String owner) {
            if (owner.equals(pagesOwner) && pages != null) return;
            pagesOwner = owner;
            pages = new ArrayList<>();
            complete = false;
            SharedPreferences p = prefs();
            if (p == null) return;
            String saved = p.getString("pages_" + owner, "");
            for (String s : saved.split(",")) {
                if (!s.isEmpty()) {
                    try { pages.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
                }
            }
            complete = p.getBoolean("complete_" + owner, false) && !pages.isEmpty();
        }

        private static void save(String owner) {
            SharedPreferences p = prefs();
            if (p == null) return;
            StringBuilder b = new StringBuilder();
            for (Long c : pages) {
                if (b.length() > 0) b.append(',');
                b.append(c);
            }
            p.edit().putString("pages_" + owner, b.toString()).putBoolean("complete_" + owner, complete).apply();
        }

        /** Every page, newest to oldest, keeping each page's starting cursor. Resumes if cut off. */
        private static void walkAll(String owner, Object[] args) {
            if (pages.isEmpty()) pages.add(0L);
            long cursor = pages.get(pages.size() - 1);
            int failures = 0;
            Utils.showToastLong(pages.size() > 1
                    ? "Still sorting your favourites oldest first, carrying on in the background."
                    : "Sorting your favourites oldest first in the background (about 10 minutes, once). "
                        + "Newest first until then.");
            while (true) {
                Object page = fetch(args, cursor);
                long next = page == null ? 0 : nextCursor(page);
                boolean more = page != null && hasMore(page);
                // A failure is no reply, an error, or a cursor that doesn't move on. An empty page
                // that moves on is a stretch of unavailable videos, and fine.
                if (page == null || statusCode(page) != 0 || more && (next <= 0 || cursor != 0 && next >= cursor)) {
                    if (++failures >= 3) { save(owner); return; }   // carry on next opening
                    sleep(3000);
                    continue;
                }
                failures = 0;
                if (!more) {
                    complete = true;
                    save(owner);
                    Cleancut.favLog("favourites indexed: " + pages.size() + " pages");
                    Utils.showToastLong("Favourites are sorted oldest first. Open Favourites again to see them.");
                    return;
                }
                pages.add(next);
                cursor = next;
                if (pages.size() % 10 == 0) save(owner);
                if (pages.size() % 30 == 0) {
                    Utils.showToastShort("Sorting favourites oldest first… about " + (pages.size() * PAGE) + " done");
                }
                sleep(150);
            }
        }

        /** New favourites since last time: walk the top until it meets the kept pages. */
        private static void refreshTop(String owner, Object[] args) {
            if (pages.size() < 2) {          // one page only: just walk it again
                pages.clear();
                complete = false;
                walkAll(owner, args);
                return;
            }
            long keptTop = pages.get(1);     // where the newest kept page ended
            List<Long> top = new ArrayList<>();
            top.add(0L);
            long cursor = 0;
            for (int i = 0; i < 60; i++) {
                Object page = fetch(args, cursor);
                // A failed or empty reply never replaces what's kept.
                if (page == null || statusCode(page) != 0 || itemCount(page) == 0) return;
                long next = nextCursor(page);
                if (!hasMore(page) || next <= 0) {
                    if (pages.size() > top.size() + 2) return;   // implausible: keep what we have
                    pages = top;              // everything fits in these pages
                    save(owner);
                    return;
                }
                top.add(next);
                if (next <= keptTop) break;
                cursor = next;
                sleep(150);
            }
            long last = top.get(top.size() - 1);
            for (int i = 1; i < pages.size(); i++) {
                if (pages.get(i) < last) top.add(pages.get(i));
            }
            if (!top.equals(pages)) {
                pages = top;
                save(owner);
            }
        }

        private static Object fetch(Object[] args, long cursor) {
            try {
                return fetchOrThrow(args, cursor);
            } catch (Throwable t) {
                Cleancut.favLog("favourites walk at " + cursor + ": " + t);
                return null;
            }
        }

        /** TikTok's own fetch for one page at a real cursor, with this thread let through. */
        static Object fetchOrThrow(Object[] args, long cursor) throws Throwable {
            Object[] a = args.clone();
            a[5] = cursor;
            a[6] = PAGE;
            INSIDE.set(true);
            try {
                return fetcher.invoke(null, a);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            } finally {
                INSIDE.set(false);
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private static long nextCursor(Object page) {
        try {
            long c = (Long) invoke(page, "getCursor");
            if (c > 0) return c;
            return (Long) invoke(page, "getMaxCursor");
        } catch (Throwable t) {
            return 0;
        }
    }

    /** TikTok's status_code on a reply (0 is fine); -1 if it can't be read. */
    private static int statusCode(Object page) {
        for (Class<?> c = page.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("status_code");
                f.setAccessible(true);
                return f.getInt(page);
            } catch (NoSuchFieldException ignored) {
                // up a class
            } catch (Throwable t) {
                return -1;
            }
        }
        return 0;
    }

    private static int itemCount(Object page) {
        try {
            List<?> items = (List<?>) invoke(page, "getItems");
            return items == null ? 0 : items.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean hasMore(Object page) {
        try {
            return (Integer) invoke(page, "getHasMore") == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?> type, Object value)
            throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name, type);
        return m.invoke(target, value);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SharedPreferences prefs() {
        Context c = Utils.getContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
