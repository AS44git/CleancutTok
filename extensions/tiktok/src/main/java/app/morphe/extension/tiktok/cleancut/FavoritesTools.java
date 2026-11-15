/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/**
 * Long-press Posts in the Favourites row: tools for favourite videos and liked videos.
 *
 * <ul>
 *   <li>Favourites: remove those saved in a year, or the oldest N. They're listed through
 *       TikTok's own request for the Favourites tab (the one it makes as you scroll it), which
 *       pages by save time, newest first; a year is the list from 1 January of the next year
 *       down to 1 January of that year, the page crossing the boundary walked one at a time.</li>
 *   <li>Liked videos: remove the most recent N, through TikTok's own request for the Liked tab.
 *       TikTok only lists recent likes, so it's newest first; removing them lets older ones
 *       appear for the next run.</li>
 *   <li>Each is undone with TikTok's request for it ({@code /aweme/v1/aweme/collect/?action=0},
 *       {@code /aweme/v1/commit/item/digg/?type=0}), one at a time at a steady, slightly varied
 *       pace, with a daily limit; refusals slow it down, then stop it until tomorrow. What's left
 *       is kept, so it continues from the menu.</li>
 *   <li>Remove unavailable favourite videos: what TikTok's own banner does.</li>
 * </ul>
 * Nothing is removed until what was found has been shown and confirmed.
 */
public final class FavoritesTools {
    private static final String HOST = "https://api16-va.tiktokv.com";
    private static final String PREFS = "cleancut_favorites";
    private static volatile boolean busy;
    /** Set by "Stop removing": the only thing that ends a run early. */
    private static volatile boolean stopAsked;
    /** How many likes the chosen run is for (the list is read again from the top as they go). */
    private static volatile int likesWanted;
    private static final Random RANDOM = new Random();

    /** What a run removes, and how gently. */
    private enum Kind {
        FAVOURITES("favourites", "remove_pending", 1500, 700, false),
        LIKES("likes", "likes_pending", 10000, 300, true);

        final String noun;
        final String key;
        /** At most this many a day. */
        final int daily;
        /** Milliseconds between removals (plus up to a third as much again at random). */
        final long every;

        /** TikTok's own app sends this one as a POST (parameters in the address). */
        final boolean post;

        Kind(String noun, String key, int daily, long every, boolean post) {
            this.noun = noun;
            this.key = key;
            this.daily = daily;
            this.every = every;
            this.post = post;
        }

        String url(String id) {
            return this == FAVOURITES
                    ? HOST + "/aweme/v1/aweme/collect/?action=0&aweme_id=" + id
                    : HOST + "/aweme/v1/commit/item/digg/?type=0&aweme_id=" + id;
        }
    }

    private FavoritesTools() {}

    // --- the menu ----------------------------------------------------------------------------

    static void menu() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (busy) {
            labels.add("Stop removing (keeps its place)");
            actions.add(() -> {
                stopAsked = true;
                Utils.showToastShort("Stopping…");
            });
        }
        for (Kind k : Kind.values()) {
            List<String> pending = pending(k);
            int count = k == Kind.LIKES ? Math.max(pending.size(), targetLeft(k)) : pending.size();
            if (count == 0) continue;
            labels.add("Continue removing " + k.noun + " (" + fmt(count) + " left)");
            actions.add(() -> {
                likesWanted = count;
                if (pending.isEmpty()) {
                    // Likes with none listed yet: they're read from the top of the Liked list as it goes.
                    confirmRemove(k, Collections.singletonList(""), "Continue removing " + fmt(count) + " " + k.noun + "?", null);
                } else {
                    confirmRemove(k, pending, "Continue removing " + fmt(count) + " " + k.noun + "?", null);
                }
            });
        }
        labels.add("Favourites: remove those from a year…");
        actions.add(FavoritesTools::pickYear);
        labels.add("Favourites: remove my oldest…");
        actions.add(FavoritesTools::pickOldest);
        labels.add("Favourites: remove unavailable videos");
        actions.add(FavoritesTools::removeUnavailable);
        labels.add("Favourites: flush out hidden ones (the last few that won't show)");
        actions.add(FavoritesTools::flushHidden);
        labels.add("Liked: remove my most recent likes…");
        actions.add(FavoritesTools::pickLikes);
        labels.add("Favourites: remove the ones from my TikTok data file");
        actions.add(() -> importFile(Kind.FAVOURITES));
        int foundCount = foundLikes().size();
        if (foundCount > 0) {
            labels.add("Liked: unlike the " + fmt(foundCount) + " found likes");
            actions.add(FavoritesTools::unlikeFound);
        }
        labels.add("Liked: look back through the years for hidden likes");
        actions.add(FavoritesTools::sweepLikes);
        int setAside = gone().size();
        if (setAside > 0) {
            labels.add("Liked: try the " + fmt(setAside) + " set-aside likes again");
            actions.add(FavoritesTools::retryGone);
            labels.add("Liked: test other ways on the set-aside likes");
            actions.add(FavoritesTools::probeGone);
        }
        labels.add("Speed: " + SPEEDS[speed()] + " (tap to change)");
        actions.add(FavoritesTools::pickSpeed);
        labels.add("Liked: remove the likes from my TikTok data file");
        actions.add(() -> importFile(Kind.LIKES));
        labels.add("Liked: count my liked videos");
        actions.add(FavoritesTools::countLiked);
        boolean oldest = FavoritesOrder.oldestFirst();
        labels.add(oldest ? "Show favourites newest first" : "Show favourites oldest first");
        actions.add(() -> {
            FavoritesOrder.setOldestFirst(!oldest);
            Utils.showToastLong((oldest ? "Newest" : "Oldest") + " first. Open Favourites again to see it.");
        });
        new AlertDialog.Builder(activity)
                .setTitle("Favourites & likes")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private static void pickYear() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        int now = Calendar.getInstance().get(Calendar.YEAR);
        List<String> years = new ArrayList<>();
        for (int y = now; y >= 2016; y--) years.add(String.valueOf(y));
        new AlertDialog.Builder(activity)
                .setTitle("Remove favourites saved in…")
                .setItems(years.toArray(new String[0]), (d, which) -> {
                    int year = Integer.parseInt(years.get(which));
                    work("Counting your favourites from " + year + "…", () -> {
                        List<String> ids = listBetween(startOfYear(year + 1), startOfYear(year), "from " + year);
                        Collections.reverse(ids);      // oldest first
                        ToolNotice.clear();
                        Utils.runOnMainThread(() -> confirmRemove(Kind.FAVOURITES, ids,
                                "Remove " + fmt(ids.size()) + " favourites saved in " + year + "?",
                                "Oldest first. " + plan(Kind.FAVOURITES, ids.size())));
                    });
                })
                .show();
    }

    private static void pickOldest() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        String[] choices = {"100", "250", "500", "1,000", "1,500 (a day's limit)"};
        int[] amounts = {100, 250, 500, 1000, 1500};
        new AlertDialog.Builder(activity)
                .setTitle("Remove my oldest favourites…")
                .setItems(choices, (d, which) -> {
                    int n = amounts[which];
                    work("Finding your oldest " + fmt(n) + " favourites…", () -> {
                        List<String> ids = oldest(n);
                        ToolNotice.clear();
                        Utils.runOnMainThread(() -> confirmRemove(Kind.FAVOURITES, ids,
                                "Remove your oldest " + fmt(ids.size()) + " favourites?",
                                "Oldest first. " + plan(Kind.FAVOURITES, ids.size())));
                    });
                })
                .show();
    }

    private static void pickLikes() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        String[] choices = {"100", "250", "500", "1,000", "2,000", "5,000"};
        int[] amounts = {100, 250, 500, 1000, 2000, 5000};
        new AlertDialog.Builder(activity)
                .setTitle("Remove my most recent likes…")
                .setItems(choices, (d, which) -> {
                    int n = amounts[which];
                    likesWanted = n;
                    setFromFile(false);
                    retryingGone = false;
                    work("Finding your " + fmt(n) + " most recent likes… (progress in your notifications)", () -> {
                        LikesScan scan = scanLikes(n, new HashSet<>(), true,
                                (t, f, w) -> ToolNotice.scan("Finding your likes", t, f, w));
                        ToolNotice.clear();
                        Utils.runOnMainThread(() -> {
                            if (scan.ids.isEmpty()) {
                                showNoLikes(scan);
                                return;
                            }
                            confirmRemove(Kind.LIKES, scan.ids,
                                    "Unlike your " + fmt(Math.max(scan.ids.size(), n)) + " most recent liked videos?",
                                    "Found " + fmt(scan.ids.size()) + " to start with"
                                            + (scan.total >= 0 ? " (TikTok says you have " + fmt(scan.total) + " likes in all)" : "")
                                            + ". Newest first; TikTok lists your likes in stretches, so the rest are read from your list as these go. "
                                            + plan(Kind.LIKES, Math.max(scan.ids.size(), n)));
                        });
                    });
                })
                .show();
    }

    // --- likes TikTok won't remove (the video's gone) -----------------------------------------

    private static List<String> gone() {
        SharedPreferences p = prefs();
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String s : p.getString("likes_gone", "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static void saveGone(java.util.Collection<String> ids, boolean replace) {
        SharedPreferences p = prefs();
        if (p == null) return;
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        if (!replace) all.addAll(gone());
        all.addAll(ids);
        p.edit().putString("likes_gone", android.text.TextUtils.join(",", all)).apply();
    }

    /** Set when the set-aside ones are being tried again: the run starts their list afresh. */
    private static volatile boolean retryingGone;

    private static void retryGone() {
        List<String> ids = gone();
        if (ids.isEmpty()) return;
        likesWanted = ids.size();
        setFromFile(true);
        retryingGone = true;
        confirmRemove(Kind.LIKES, ids, "Try the " + fmt(ids.size()) + " set-aside likes again?",
                "These are the ones TikTok refused (mostly \"Video has been removed\"). Any whose video is back "
                        + "get unliked; the rest are kept for another try. " + plan(Kind.LIKES, ids.size()));
    }

    private static String code(String raw) {
        try {
            JSONObject r = new JSONObject(raw);
            String m = r.optString("status_msg", "");
            return r.optInt("status_code", -1) + (m.isEmpty() ? "" : " " + m);
        } catch (Throwable e) {
            return "not JSON: " + (raw == null ? "null" : raw.substring(0, Math.min(80, raw.length())));
        }
    }

    private static String err(Throwable e) {
        Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return "error: " + c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " " + c.getMessage());
    }

    /** TikTok's own way of sending a like: its Retrofit API, the one the heart button uses. */
    private static String viaTikTok(String id) throws Exception {
        Class<?> api = Class.forName("com.ss.android.ugc.aweme.feed.api.FeedActionApi");
        java.lang.reflect.Field field = api.getDeclaredField("LIZIZ");
        field.setAccessible(true);
        Object retrofit = field.get(null);
        Map<String, String> m = new HashMap<>();
        m.put("aweme_id", id);
        m.put("type", "0");
        m.put("channel_id", "0");
        Method call = Class.forName("com.ss.android.ugc.aweme.feed.api.FeedActionApi$RetrofitApi")
                .getMethod("LIZIZ", Map.class);
        Object future = call.invoke(retrofit, m);
        Object resp = ((java.util.concurrent.Future<?>) future).get(20, java.util.concurrent.TimeUnit.SECONDS);
        int sc = -1;
        String msg = "";
        for (Class<?> c = resp.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("status_code")) sc = ((Number) f.get(resp)).intValue();
                if (f.getName().equals("status_msg") && f.get(resp) != null) msg = String.valueOf(f.get(resp));
            }
        }
        return "{\"status_code\":" + sc + ",\"status_msg\":" + JSONObject.quote(msg) + "}";
    }

    /** The API host TikTok itself is using right now (it picks one per region). */
    private static String tiktokHost() {
        try {
            java.lang.reflect.Field f1 = Class.forName("X.02r9").getDeclaredField("LJIIIIZZ");
            f1.setAccessible(true);
            Object cfg = f1.get(null);
            java.lang.reflect.Field f2 = cfg.getClass().getDeclaredField("LIZ");
            f2.setAccessible(true);
            Object h = f2.get(cfg);
            if (h != null && !String.valueOf(h).isEmpty()) return "https://" + h;
        } catch (Throwable ignored) {
            // not this version's layout
        }
        return null;
    }

    private interface Way {
        String send(String id) throws Exception;
    }

    /**
     * Tries the set-aside likes every other way TikTok's app could send an unlike, a few videos
     * each, and says which (if any) TikTok accepts.
     */
    private static void probeGone() {
        List<String> all = gone();
        if (all.isEmpty()) return;
        List<String> ids = all.subList(0, Math.min(4, all.size()));
        work("Testing other ways on " + ids.size() + " set-aside likes…", () -> {
            final String digg = "/aweme/v1/commit/item/digg/?type=0&aweme_id=";
            final String extra = "&channel_id=0&enter_from=personal_homepage&previous_page=personal_homepage";
            List<String> names = new ArrayList<>();
            List<Way> ways = new ArrayList<>();
            names.add("the usual (POST)");
            ways.add(id -> post(HOST + digg + id));
            names.add("GET");
            ways.add(id -> get(HOST + digg + id));
            names.add("with the app's extra fields");
            ways.add(id -> post(HOST + digg + id + extra));
            names.add("TikTok's own like button route");
            ways.add(FavoritesTools::viaTikTok);
            String own = tiktokHost();
            if (own != null && !own.equals(HOST)) {
                names.add("TikTok's current server (" + own.replace("https://", "") + ")");
                ways.add(id -> post(own + digg + id));
            }
            for (String h : new String[]{"api16-normal-c-useast1a.tiktokv.com", "api22-normal-c-alisg.tiktokv.com",
                    "api16-normal-c-alisg.tiktokv.com", "api.tiktokv.com"}) {
                names.add("server " + h);
                ways.add(id -> post("https://" + h + digg + id));
            }
            names.add("unlogged digg");
            ways.add(id -> post(HOST + "/aweme/v1/unlogged/digg/?type=0&aweme_id=" + id));

            StringBuilder report = new StringBuilder();
            List<String> worked = new ArrayList<>();
            Set<String> freed = new HashSet<>();
            int step = 0;
            int steps = ways.size() * ids.size() + ids.size();
            report.append("Videos: ");
            for (String id : ids) {
                String detail;
                try {
                    detail = code(get(HOST + "/aweme/v1/aweme/detail/?aweme_id=" + id));
                } catch (Throwable e) {
                    detail = err(e);
                }
                report.append("\n  ").append(id).append(": ").append(detail);
                ToolNotice.scan("Testing set-aside likes", "Looking at the videos…", ++step, steps);
            }
            for (int w = 0; w < ways.size(); w++) {
                int yes = 0;
                StringBuilder line = new StringBuilder();
                for (String id : ids) {
                    if (freed.contains(id)) continue;
                    String r;
                    try {
                        r = code(ways.get(w).send(id));
                    } catch (Throwable e) {
                        r = err(e);
                    }
                    if (r.startsWith("0")) {
                        yes++;
                        freed.add(id);
                    }
                    line.append("\n    ").append(id).append(": ").append(r);
                    ToolNotice.scan("Testing set-aside likes", names.get(w) + "…", ++step, steps);
                    sleep(300);
                }
                report.append("\n").append(names.get(w)).append(": ").append(yes).append(" yes").append(line);
                if (yes > 0) worked.add(names.get(w));
            }
            long likes = likesNow();
            report.append("\nTikTok says you have ").append(likes >= 0 ? fmt(likes) : "?").append(" likes now.");
            Cleancut.favLog("set-aside test:\n" + report);
            ToolNotice.clear();
            if (!freed.isEmpty()) {
                List<String> rest = new ArrayList<>(all);
                rest.removeAll(freed);
                saveGone(rest, true);
            }
            String text = report.toString();
            String head = worked.isEmpty()
                    ? "None of the other ways got a yes for these videos."
                    : "A yes from: " + android.text.TextUtils.join(", ", worked) + ". Send this to Claude to build it in.";
            Utils.runOnMainThread(() -> {
                Activity a = Utils.getVisibleActivity();
                if (a == null) return;
                new AlertDialog.Builder(a)
                        .setTitle("Set-aside likes: test")
                        .setMessage(head + "\n\n" + text)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Copy details", (d, x) -> {
                            try {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                        a.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("TT test details", head + "\n\n" + text));
                                Utils.showToastShort("Copied");
                            } catch (Throwable ignored) {
                                // nothing to copy to
                            }
                        })
                        .show();
            });
        });
    }

    /**
     * Looks for likes TikTok stopped listing: asks the Liked list (and the plain likes list) for pages
     * at hand-set dates, twice a month back to 2016, in each unit TikTok's pointers use, and follows
     * any page that answers. Anything found is kept to unlike from the menu.
     */
    private static void sweepLikes() {
        work("Looking back through the years for hidden likes…", () -> {
            knowMe();
            Set<String> seen = new HashSet<>();
            List<String> found = new ArrayList<>();
            Map<String, Integer> byYear = new java.util.TreeMap<>();
            StringBuilder notes = new StringBuilder();
            int asked = 0, answered = 0, errors = 0, hidden = 0;
            String lastError = "";
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_MONTH, c.get(Calendar.DAY_OF_MONTH) > 15 ? 15 : 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            List<Long> points = new ArrayList<>();
            while (c.get(Calendar.YEAR) >= 2016) {
                points.add(c.getTimeInMillis());
                if (c.get(Calendar.DAY_OF_MONTH) == 15) c.set(Calendar.DAY_OF_MONTH, 1);
                else {
                    c.add(Calendar.MONTH, -1);
                    c.set(Calendar.DAY_OF_MONTH, 15);
                }
            }
            String title = "Looking for hidden likes";
            for (int i = 0; i < points.size(); i++) {
                long ms = points.get(i);
                String when = new SimpleDateFormat("MMM yyyy", Locale.US).format(new Date(ms));
                ToolNotice.scan(title, when + " · " + fmt(found.size()) + " found", i, points.size());
                // The Liked list the app uses (its pointer is in microseconds), then the plain list in each unit.
                for (int way = 0; way < 4; way++) {
                    long cursor = way == 0 ? ms * 1000 : way == 1 ? ms : way == 2 ? ms / 1000 : ms * 1000;
                    for (int page = 0; page < 40; page++) {
                        List<String> got = new ArrayList<>();
                        long next = 0;
                        boolean more = false;
                        String err = null;
                        for (int attempt = 0; attempt < 2; attempt++) {     // a hiccup is asked again once
                            asked++;
                            try {
                                if (way == 0) {
                                    FavoritesOrder.Page p = FavoritesOrder.likedPage(cursor, 30, ownUid, ownSecUid);
                                    got.addAll(p.ids);
                                    for (String x : p.invalid) {
                                        got.add(x);
                                        hidden++;
                                    }
                                    next = p.next;
                                    more = p.more;
                                } else {
                                    String raw = get(HOST + "/aweme/v1/aweme/favorite/?user_id=" + ownUid + "&sec_user_id="
                                            + ownSecUid + "&max_cursor=" + cursor + "&min_cursor=0&count=30");
                                    JSONObject r = new JSONObject(raw);
                                    if (r.optInt("status_code", -1) != 0) throw new Exception("status " + r.optInt("status_code", -1)
                                            + " " + r.optString("status_msg", ""));
                                    got.addAll(ids(r));
                                    next = r.optLong("max_cursor", 0);
                                    more = r.optInt("has_more", 0) == 1;
                                }
                                answered++;
                                err = null;
                                break;
                            } catch (Throwable t) {
                                err = String.valueOf(t.getMessage());
                                sleep(1500);
                            }
                        }
                        if (err != null) {
                            errors++;
                            lastError = err;
                            break;
                        }
                        int fresh = 0;
                        for (String id : got) {
                            if (id != null && !id.isEmpty() && seen.add(id)) {
                                found.add(id);
                                fresh++;
                                String y = new SimpleDateFormat("yyyy", Locale.US).format(new Date(ms));
                                Integer n = byYear.get(y);
                                byYear.put(y, n == null ? 1 : n + 1);
                            }
                        }
                        if (fresh > 0 && notes.length() < 1500) {
                            notes.append("\n  ").append(when).append(way == 0 ? " (Liked list)" : " (plain list, unit " + way + ")")
                                    .append(": ").append(fresh).append(" new, more ").append(more).append(", next ").append(next);
                        }
                        // Follow a page that gave something; one that's empty ends this date's look.
                        if (got.isEmpty() || next <= 0 || next == cursor) break;
                        cursor = next;
                        sleep(250);
                    }
                    sleep(200);
                }
                if (i % 10 == 9 && !found.isEmpty()) saveFound(found);
            }
            saveFound(found);
            ToolNotice.clear();
            long likes = likesNow();
            String text = "Dates asked: " + points.size() + " (twice a month back to 2016), requests " + asked
                    + ", answered " + answered + ", failed " + errors + (lastError.isEmpty() ? "" : " (last: " + lastError + ")")
                    + ".\nLikes found: " + fmt(found.size()) + (hidden > 0 ? " (" + hidden + " listed as unavailable)" : "")
                    + (byYear.isEmpty() ? "" : "\nBy year asked: " + byYear)
                    + (notes.length() > 0 ? "\nWhere:" + notes : "")
                    + "\nTikTok says you have " + (likes >= 0 ? fmt(likes) : "?") + " likes."
                    + "\nTabs captured: " + FavoritesOrder.capturedInfo() + ".";
            Cleancut.favLog("hidden likes sweep:\n" + text);
            Utils.runOnMainThread(() -> {
                Activity a = Utils.getVisibleActivity();
                if (a == null) return;
                AlertDialog.Builder b = new AlertDialog.Builder(a)
                        .setTitle(found.isEmpty() ? "No hidden likes turned up" : fmt(found.size()) + " likes found")
                        .setMessage((found.isEmpty()
                                ? "TikTok didn't give any pages for those dates. It can change from day to day, so it's worth trying again later.\n\n"
                                : "They're kept: choose \"Liked: unlike the found ones\" in the menu.\n\n") + text)
                        .setNeutralButton("Copy details", (d, x) -> {
                            try {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                        a.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("TT test details", text));
                                Utils.showToastShort("Copied");
                            } catch (Throwable ignored) {
                                // nothing to copy to
                            }
                        });
                if (found.isEmpty()) b.setPositiveButton("OK", null);
                else b.setPositiveButton("Unlike them now", (d, x) -> unlikeFound()).setNegativeButton("Later", null);
                b.show();
            });
        });
    }

    private static List<String> foundLikes() {
        SharedPreferences p = prefs();
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String s : p.getString("likes_found", "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static void saveFound(List<String> ids) {
        SharedPreferences p = prefs();
        if (p == null) return;
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(foundLikes());
        all.addAll(ids);
        p.edit().putString("likes_found", android.text.TextUtils.join(",", all)).apply();
    }

    private static void unlikeFound() {
        List<String> ids = foundLikes();
        if (ids.isEmpty()) return;
        likesWanted = ids.size();
        setFromFile(true);
        retryingGone = false;
        SharedPreferences p = prefs();
        if (p != null) p.edit().remove("likes_found").apply();     // the run takes them; set-aside ones are kept there
        confirmRemove(Kind.LIKES, ids, "Unlike the " + fmt(ids.size()) + " found likes?",
                "These turned up looking back through the years. " + plan(Kind.LIKES, ids.size()));
    }

    // --- speed ---------------------------------------------------------------------------------

    private static final String[] SPEEDS = {"Normal", "Fast", "Very fast", "Max"};
    /** Milliseconds between removals at each speed: likes, favourites. */
    private static final int[] LIKES_MS = {1600, 300, 120, 0};
    private static final int[] FAV_MS = {1200, 700, 300, 0};

    private static int speed() {
        SharedPreferences p = prefs();
        int s = p == null ? 3 : p.getInt("speed", 3);
        return s < 0 || s >= SPEEDS.length ? 3 : s;
    }

    /** How many requests go at once at each speed (a refusal drops it to one until things calm down). */
    private static final int[] WIDTH = {1, 1, 4, 8};         // where it starts
    private static final int[] MAX_WIDTH = {1, 1, 10, 24};   // the most it climbs to while TikTok keeps saying yes
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(26, r -> {
        Thread t = new Thread(r, "cleancut-unlike");
        t.setDaemon(true);
        return t;
    });

    private static long every(Kind k) {
        return (k == Kind.LIKES ? LIKES_MS : FAV_MS)[speed()];
    }

    private static void pickSpeed() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        String[] labels = {
                "Normal: about 1.5 s each (the gentlest)",
                "Fast: about 0.3 s each",
                "Very fast: 4 at a time, up to 8 while TikTok says yes",
                "Max: 8 at a time, up to 24 while TikTok says yes (finds its own speed)",
        };
        new AlertDialog.Builder(activity)
                .setTitle("Removing speed")
                .setSingleChoiceItems(labels, speed(), (d, which) -> {
                    SharedPreferences p = prefs();
                    if (p != null) p.edit().putInt("speed", which).apply();
                    Utils.showToastLong("Speed: " + SPEEDS[which] + ". It applies to the next run, or the one going now.");
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void setFromFile(boolean on) {
        fromFile = on;
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean("likes_from_file", on).apply();
    }

    /** A run that was going when TikTok closed (or the phone restarted) carries on by itself. */
    private static void markRunning(Kind k, boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(k.key + "_running", on).apply();
    }

    private static long lastResumeTry;

    /** Called whenever a profile tab loads: picks an interrupted run back up, no menu needed. */
    public static void resumeIfInterrupted() {
        try {
            if (busy) return;
            long now = System.currentTimeMillis();
            if (now - lastResumeTry < 20_000) return;
            lastResumeTry = now;
            SharedPreferences p = prefs();
            if (p == null) return;
            for (Kind k : Kind.values()) {
                if (!p.getBoolean(k.key + "_running", false)) continue;
                List<String> left = pending(k);
                if (left.isEmpty() && targetLeft(k) <= 0) {
                    markRunning(k, false);
                    continue;
                }
                Cleancut.favLog("carrying on with " + k.noun + " (" + left.size() + " left)");
                Utils.showToastShort("Carrying on removing " + k.noun + "…");
                work(null, () -> removeAll(k));
                return;
            }
        } catch (Throwable e) {
            Cleancut.favLog("resume: " + e);
        }
    }

    /** Set while a run works from a data file: the Liked list isn't read again when the file's ids run out. */
    private static volatile boolean fromFile;

    /**
     * For when TikTok won't list your likes: reads the video ids from the Like List in your TikTok
     * data download (Settings > Account > Download your data), and unlikes each by id. The file goes
     * in this app's own folder: Android/data/<this app>/files/ as likes.txt (or Like List.txt).
     */
    private static void importFile(Kind kind) {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        final boolean likes = kind == Kind.LIKES;
        final String[] names = likes
                ? new String[]{"likes.txt", "Like List.txt", "like list.txt", "likes.json"}
                : new String[]{"favourites.txt", "Favourite Videos.txt", "favorites.txt"};
        work("Reading your " + kind.noun + " file…", () -> {
            java.io.File dir = Utils.getContext().getExternalFilesDir(null);
            java.io.File f = null;
            if (dir != null) {
                for (String name : names) {
                    java.io.File c = new java.io.File(dir, name);
                    if (c.isFile()) { f = c; break; }
                }
            }
            List<String> ids = new ArrayList<>();
            if (f != null) {
                java.util.regex.Pattern pat = java.util.regex.Pattern.compile("(?:video/|\"id\"\\s*:\\s*\"?)(\\d{15,22})|^(\\d{15,22})$");
                Set<String> seen = new HashSet<>();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        java.util.regex.Matcher m = pat.matcher(line.trim());
                        while (m.find()) {
                            String id = m.group(1) != null ? m.group(1) : m.group(2);
                            if (seen.add(id)) ids.add(id);      // newest first, as the file lists them
                        }
                    }
                }
            }
            ToolNotice.clear();
            final java.io.File file = f;
            final String where = dir == null ? "this app's files folder" : dir.getAbsolutePath();
            Utils.runOnMainThread(() -> {
                if (ids.isEmpty()) {
                    Activity a = Utils.getVisibleActivity();
                    if (a == null) return;
                    new AlertDialog.Builder(a)
                            .setTitle(file == null ? "No " + kind.noun + " file found" : "No videos in that file")
                            .setMessage("Put the " + (likes ? "Like List" : "Favourite Videos") + " file from your TikTok data download here, named "
                                    + names[0] + ":\n\n" + where)
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                if (likes) {
                    likesWanted = ids.size();
                    setFromFile(true);
                    retryingGone = false;
                }
                confirmRemove(kind, ids, (likes ? "Unlike " : "Remove from favourites: ") + fmt(ids.size()) + " videos from your file?",
                        "Newest first, by their ids, so TikTok's list isn't needed. " + plan(kind, ids.size()));
            });
        });
    }

    /** "About 9 minutes. Up to 1,500 a day; ..." */
    private static String plan(Kind k, int n) {
        int today = Math.max(0, k.daily - doneToday(k));
        String s = "About " + minutes(k, Math.min(n, today)) + ". Up to " + fmt(k.daily) + " " + k.noun + " a day";
        if (n > today) s += ": " + fmt(today) + " today, the rest from the menu on later days";
        return s + ". Keep TikTok open; if it's cut off, long-press Posts to continue.";
    }

    // --- listing ------------------------------------------------------------------------------

    /** Ids of favourites saved in [notBefore, before), newest first. */
    private static List<String> listBetween(long before, long notBefore, String what) throws Exception {
        List<String> out = new ArrayList<>();
        long cursor = Math.min(before, System.currentTimeMillis() / 1000);   // never a time to come
        for (int page = 0; page < 500; page++) {
            FavoritesOrder.Page p = page(cursor, 30);
            if (p.next >= notBefore || notBefore == 0) {
                out.addAll(p.ids);
                if (!p.more || p.next <= 0 || p.next >= cursor) break;
                cursor = p.next;
            } else {
                // This page crosses the start: one video at a time (each reply's cursor is that
                // video's save time) until the save times go past it.
                long c = cursor;
                for (int i = 0; i < 40; i++) {
                    FavoritesOrder.Page one;
                    try {
                        one = page(c, 1);
                    } catch (Exception e) {
                        Cleancut.favLog("year edge, one at a time: " + e.getMessage());
                        break;          // the few right at the edge stay; the rest is exact
                    }
                    if (one.next < notBefore) break;
                    out.addAll(one.ids);
                    if (!one.more || one.next <= 0 || one.next >= c) break;
                    c = one.next;
                }
                break;
            }
            ToolNotice.scan("Finding favourites", fmt(out.size()) + " found " + what + " · page " + (page + 1), out.size(), 0);
            if (page % 10 == 9) Utils.showToastShort("Counting favourites " + what + "… " + fmt(out.size()) + " so far");
        }
        return out;
    }

    /** The oldest n favourites, oldest first: a year at a time from the oldest year. */
    private static List<String> oldest(int n) throws Exception {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        int first = -1;
        for (int y = 2014; y <= now; y++) {
            if (startOfYear(y + 1) > System.currentTimeMillis() / 1000) { first = y; break; }
            if (!page(startOfYear(y + 1), 30).ids.isEmpty()) { first = y; break; }
        }
        List<String> out = new ArrayList<>();
        if (first < 0) return out;
        for (int y = first; y <= now && out.size() < n; y++) {
            List<String> year = listBetween(startOfYear(y + 1), startOfYear(y), "from " + y);
            Collections.reverse(year);
            out.addAll(year);
        }
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    private static String ownUid, ownSecUid;

    /** Your own user id, so only your own lists are ever used. */
    private static void knowMe() throws Exception {
        if (ownUid != null) return;
        JSONObject user = new JSONObject(get(HOST + "/aweme/v1/user/profile/self/")).optJSONObject("user");
        if (user == null) throw new Exception("couldn't read your profile; check you're online and try again");
        ownUid = user.optString("uid", "");
        ownSecUid = user.optString("sec_uid", "");
    }

    /** How many more times to ask again, a while apart, when TikTok's Liked list comes back empty. */
    private static final int RETRIES = 7;

    /** What looking through the Liked list found, and what TikTok said while it did. */
    private static final class LikesScan {
        final List<String> ids = new ArrayList<>();
        int pages, emptyPages, errors, restarts, skips, hidden;
        String hiddenInfo = "";
        /** Your like count, from your profile (-1 if it didn't say). */
        long total = -1;
        String last = "", how = "", direct = "";

        String report() {
            StringBuilder b = new StringBuilder();
            if (total >= 0) b.append("TikTok says you have ").append(fmt(total)).append(" likes.\n");
            b.append("Pages read: ").append(pages).append(" (").append(emptyPages).append(" empty)");
            if (errors > 0) b.append(", failed requests: ").append(errors);
            b.append(".\nLast reply: ").append(last.isEmpty() ? "none" : last).append('.');
            if (hidden > 0) b.append("\nHidden ones TikTok counts but won't show: ").append(hidden).append('.');
            if (!hiddenInfo.isEmpty()) b.append("\nIts own counts of unavailable ones: ").append(hiddenInfo).append('.');
            b.append("\nTabs captured: ").append(FavoritesOrder.capturedInfo()).append('.');
            if (skips > 0) b.append("\nFollowed TikTok's pointer past ").append(skips).append(" empty pages.");
            if (restarts > 0) b.append("\nLooked again from the top ").append(restarts).append(restarts == 1 ? " time." : " times.");
            if (!FavoritesOrder.likedBase.isEmpty()) b.append("\nRequest copied from: ").append(FavoritesOrder.likedBase).append('.');
            if (!direct.isEmpty()) b.append("\nTikTok's direct list: ").append(direct).append('.');
            if (!how.isEmpty()) b.append('\n').append(how);
            return b.toString();
        }
    }

    /** Where a search says how it's going. */
    private interface Tell {
        void say(String text, int found, int wanted);
    }

    /**
     * Your n most recent likes, newest first, through TikTok's own Liked tab request, skipping any in
     * {@code skip}. Built not to give up early: a page that fails is asked again (a few times), an empty
     * page with more after it is read past (TikTok keeps just-unliked videos at the top, hidden), an
     * empty list is asked for again from the top after a pause (another page size, another tab's
     * request), and, if that finds nothing, TikTok's plain list is tried. It says how it's going the
     * whole way, and what it found stays found if a later page fails.
     */
    private static LikesScan scanLikes(int n, Set<String> skip, boolean first, Tell tell) {
        LikesScan s = new LikesScan();
        try {
            knowMe();
        } catch (Exception e) {
            s.how = String.valueOf(e.getMessage());
            Cleancut.favLog("likes scan: " + e);
            return s;
        }
        if (first) s.total = likesNow();
        tell.say("Starting…" + (s.total >= 0 ? " You have " + fmt(s.total) + " likes." : ""), 0, n);
        Set<String> seen = new HashSet<>(skip);
        long cursor = 0;
        int count = 30, emptyRun = 0, failures = 0, tried = 0;
        long deadline = System.currentTimeMillis() + 25 * 60_000L;
        while (s.ids.size() < n && tried++ < 4000 && System.currentTimeMillis() < deadline) {
            FavoritesOrder.Page p;
            try {
                p = FavoritesOrder.likedPage(cursor, count, ownUid, ownSecUid);
            } catch (Exception e) {
                s.errors++;
                s.last = String.valueOf(e.getMessage());
                Cleancut.favLog("likes scan, page at " + cursor + ": " + e);
                if (String.valueOf(e.getMessage()).startsWith("open your own")) {
                    s.how = String.valueOf(e.getMessage());
                    break;
                }
                if (++failures >= 6) {
                    s.how = "TikTok kept failing (" + e.getMessage() + ").";
                    break;
                }
                tell.say("TikTok didn't answer, trying again (" + failures + " of 6)…", s.ids.size(), n);
                sleep(2000L * failures);
                if (failures == 3) FavoritesOrder.retryLiked(false);
                continue;
            }
            failures = 0;
            s.pages++;
            int added = 0;
            for (String id : p.ids) {
                if (seen.add(id)) {
                    s.ids.add(id);
                    added++;
                    if (s.ids.size() >= n) break;
                }
            }
            for (String id : p.invalid) {
                if (s.ids.size() < n && seen.add(id)) {
                    s.ids.add(id);
                    s.hidden++;
                    added++;
                }
            }
            if (!p.invalidInfo.isEmpty() && !p.invalidInfo.equals("null/null/null/null")) s.hiddenInfo = p.invalidInfo;
            if (p.ids.isEmpty() && p.invalid.isEmpty()) s.emptyPages++;
            emptyRun = added == 0 ? emptyRun + 1 : 0;
            s.last = "got " + p.ids.size() + ", more " + (p.more ? "yes" : "no") + ", next " + p.next;
            tell.say(fmt(s.ids.size()) + (n > 0 ? " of " + fmt(n) : "") + " found · page " + s.pages
                    + (emptyRun >= 5 ? " · reading past likes TikTok is still hiding…" : ""), s.ids.size(), n);
            if (s.pages % 10 == 0) Utils.showToastShort("Finding likes… " + fmt(s.ids.size()) + " so far");
            // An empty page that carries a fresh pointer (TikTok hides the likes just removed, and says
            // "no more" even so) is skipped past by following the pointer.
            boolean skipOn = p.ids.isEmpty() && p.next > 0 && p.next != cursor && s.skips < 400;
            if (!p.more && skipOn) s.skips++;
            if (s.pages <= 3 || (!p.more && skipOn && s.skips <= 3)) {
                Cleancut.favLog("likes scan page " + s.pages + " at " + cursor + ": " + s.last + (skipOn ? " (following)" : ""));
            }
            boolean end = p.next <= 0 || p.next == cursor || (!p.more && !skipOn);
            if (end) {
                if (s.ids.isEmpty() && s.restarts < RETRIES) {
                    // Nothing at all: TikTok's list can lag and show up a few tries later. Wait, then
                    // look again, a little differently each time: where it starts ("now" in each unit
                    // TikTok uses), the page size, and which tab's request it's made from.
                    s.restarts++;
                    long wait = Math.min(30_000L, 8_000L * s.restarts);
                    for (long left = wait; left > 0; left -= 1000) {
                        tell.say("TikTok's list is empty. Trying again in " + (left / 1000) + "s (attempt "
                                + (s.restarts + 1) + " of " + (RETRIES + 1) + ")…", 0, n);
                        sleep(1000);
                    }
                    likesNow();      // a look at your profile too, in case it nudges TikTok's side
                    FavoritesOrder.retryLiked(s.restarts % 2 == 0);
                    long ms = System.currentTimeMillis();
                    long[] starts = {0, 0, ms * 1000, ms, ms / 1000, 0, ms * 1000, ms};
                    cursor = starts[Math.min(s.restarts, starts.length - 1)];
                    emptyRun = 0;
                    count = new int[]{30, 30, 20, 10, 50, 30, 20, 10, 30}[Math.min(s.restarts, 8)];
                    continue;
                }
                break;
            }
            if (emptyRun >= 1500) {
                s.how = "Read 1,500 pages in a row with no likes in them, so it stopped.";
                break;
            }
            cursor = p.next;
            sleep(300);
        }
        if (s.ids.isEmpty()) {
            tell.say("Trying TikTok's plain list…", 0, n);
            directLikes(s, n, seen, tell);
            if (!s.ids.isEmpty()) s.how = "";
        }
        if (s.how.isEmpty() && !s.ids.isEmpty() && s.ids.size() < n) s.how = "That's all TikTok lists right now.";
        Cleancut.favLog("likes scan: found " + s.ids.size() + " of " + n + ". " + s.report().replace('\n', ' '));
        return s;
    }

    /** TikTok's plain Liked list (a signed request of its own), as a last way to find them. */
    private static void directLikes(LikesScan s, int n, Set<String> seen, Tell tell) {
        long cursor = 0;
        for (int guard = 0; guard < 600 && s.ids.size() < n; guard++) {
            String raw;
            try {
                raw = get(HOST + "/aweme/v1/aweme/favorite/?user_id=" + ownUid + "&sec_user_id=" + ownSecUid
                        + "&max_cursor=" + cursor + "&min_cursor=0&count=30");
            } catch (Throwable t) {
                s.direct = "no answer (" + t.getMessage() + ")";
                return;
            }
            JSONObject r;
            try {
                r = new JSONObject(raw);
            } catch (Exception e) {
                s.direct = "not TikTok's answer";
                return;
            }
            int code = r.optInt("status_code", -1);
            if (code != 0) {
                s.direct = "status " + code;
                Cleancut.favLog("plain likes list: " + raw.substring(0, Math.min(200, raw.length())));
                return;
            }
            List<String> got = ids(r);
            for (String id : got) {
                if (seen.add(id)) {
                    s.ids.add(id);
                    if (s.ids.size() >= n) break;
                }
            }
            s.direct = got.isEmpty() && s.ids.isEmpty() ? "empty" : "worked";
            tell.say(fmt(s.ids.size()) + " found · plain list, page " + (guard + 1), s.ids.size(), n);
            long next = r.optLong("max_cursor", 0);
            boolean skipOn = got.isEmpty() && next > 0 && next != cursor && s.skips < 400;
            if (r.optInt("has_more", 0) != 1 && skipOn) s.skips++;
            if (next <= 0 || next == cursor || (r.optInt("has_more", 0) != 1 && !skipOn)) return;
            cursor = next;
            sleep(400);
        }
    }

    /** Your like count, from your profile; -1 if TikTok doesn't say. */
    private static long likesNow() {
        try {
            JSONObject user = new JSONObject(get(HOST + "/aweme/v1/user/profile/self/")).optJSONObject("user");
            return user == null ? -1 : user.optLong("favoriting_count", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The search found no likes: say why in words and numbers, with a button to copy them. */
    private static void showNoLikes(LikesScan s) {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) {
            ToolNotice.finish("No likes found", "TikTok isn't listing your likes right now. Open the TT test menu to see why.");
            return;
        }
        String text = s.report();
        new AlertDialog.Builder(activity)
                .setTitle("No likes found")
                .setMessage("TikTok isn't listing any of your liked videos right now.\n\n" + text
                        + "\n\nTry: close TikTok completely, open it, open your Favourites tab and scroll a little, "
                        + "then try again. If it's the same, copy these details and send them to Claude.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy details", (d, w) -> {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("TT test details", text));
                        Utils.showToastShort("Copied");
                    } catch (Throwable ignored) {
                        // nothing to copy to
                    }
                })
                .show();
    }

    /**
     * A page of your favourites through TikTok's own Favourites request (the one it makes when
     * you scroll the tab), at a gentle pace.
     */
    private static FavoritesOrder.Page page(long cursor, int count) throws Exception {
        knowMe();
        FavoritesOrder.Page p = FavoritesOrder.favoritesPage(cursor, count, ownUid, ownSecUid);
        sleep(250);
        return p;
    }

    private static List<String> ids(JSONObject json) {
        List<String> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("aweme_list");
        if (list == null) list = json.optJSONArray("items");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("aweme_id", a.optString("id", ""));
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    // --- removing -----------------------------------------------------------------------------

    private static void confirmRemove(Kind k, List<String> ids, String title, String note) {
        if (ids.isEmpty()) {
            Utils.showToastLong("Nothing to remove there.");
            return;
        }
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) {
            // TikTok isn't on screen to ask: keep what was found, ready to confirm from the menu.
            savePending(k, ids);
            saveTarget(k, k == Kind.LIKES ? Math.max(ids.size(), likesWanted) : ids.size());
            ToolNotice.finish("Found " + fmt(ids.size()) + " " + k.noun,
                    "Open TikTok, long-press Posts and choose Continue to remove them.");
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setPositiveButton("Remove", (d, w) -> {
                    ToolNotice.askPermission(activity);
                    savePending(k, ids);
                    saveTarget(k, k == Kind.LIKES ? Math.max(ids.size(), likesWanted) : ids.size());
                    work(null, () -> removeAll(k));
                })
                .setNegativeButton("Cancel", null);
        if (note != null) b.setMessage(note);
        b.show();
    }

    /**
     * Removes what's pending, in order, one at a time with a little variety in the pace, within
     * the day's limit. A video TikTok refuses is tried again later in the run, and given up after
     * three tries (it may be gone). Several refusals in a row mean TikTok wants it slower: a
     * minute's wait, then, if it goes on, a stop until tomorrow. What's left stays pending.
     */
    /**
     * TikTok drops its own requests a minute after it leaves the screen ("went background"), and the
     * screen turning off counts: so while a run goes, the screen stays on (dim it if you like).
     */
    private static void keepScreenOn(boolean on) {
        Utils.runOnMainThread(() -> {
            try {
                Activity a = Utils.getVisibleActivity();
                if (a == null) return;
                if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable ignored) {
                // best effort
            }
        });
    }

    private static void removeAll(Kind k) {
        keepScreenOn(true);
        try {
            removeAllOn(k);
        } finally {
            keepScreenOn(false);
        }
    }

    /** One request's answer: TikTok's status (0 is yes), or the error if there was no answer at all. */
    private static final class Answer {
        final String id;
        final int code;
        final String msg;
        final String raw;
        final Throwable error;
        final long ms;

        Answer(String id, int code, String msg, String raw, Throwable error, long ms) {
            this.id = id;
            this.code = code;
            this.msg = msg;
            this.raw = raw;
            this.error = error;
            this.ms = ms;
        }
    }

    private static Answer ask(String id, String url, boolean usePost) {
        long t = System.currentTimeMillis();
        try {
            String raw = usePost ? post(url) : get(url);
            long ms = System.currentTimeMillis() - t;
            try {
                JSONObject r = new JSONObject(raw);
                return new Answer(id, r.optInt("status_code", -1), r.optString("status_msg", ""), raw, null, ms);
            } catch (Exception notJson) {
                return new Answer(id, -1, "", raw, new java.io.IOException(
                        "not TikTok's answer (" + String.valueOf(raw).length() + " chars)"), ms);
            }
        } catch (Throwable e) {
            return new Answer(id, -1, "", null, e, System.currentTimeMillis() - t);
        }
    }

    /** TikTok asking to slow down, as opposed to a "no" about one video (deleted, private, gone). */
    private static boolean slowDown(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("frequen") || m.contains("too fast") || m.contains("too many") || m.contains("slow down")
                || m.contains("rate limit") || m.contains("limit") || m.contains("busy") || m.contains("later")
                || m.contains("wait");
    }

    private static boolean aboutTheVideo(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("unavailable") || m.contains("not available") || m.contains("no longer") || m.contains("deleted")
                || m.contains("doesn't exist") || m.contains("does not exist") || m.contains("not exist")
                || m.contains("private") || m.contains("removed") || m.contains("not found");
    }

    private static List<String> pendingNow(java.util.Collection<String> flying, java.util.Collection<String> queue) {
        List<String> l = new ArrayList<>(flying.size() + queue.size());
        l.addAll(flying);
        l.addAll(queue);
        return l;
    }

    /**
     * The removal engine. Requests go out side by side (how many at once climbs while TikTok keeps
     * saying yes) and each answer is dealt with as it arrives, so one slow reply holds nothing up.
     * A "no" about one video (deleted, private) just sets that video aside; only TikTok asking to slow
     * down, or a run of noes, brings a pause, and one pause covers a whole burst of them.
     */
    private static void removeAllOn(Kind k) {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (String id : pending(k)) if (!id.isEmpty()) queue.add(id);   // "" is the "continue" placeholder
        // Likes from the list: the target count; as the found ones go, the Liked list's top is read again.
        int target = k == Kind.LIKES ? Math.max(queue.size(), targetLeft(k)) : queue.size();
        Set<String> done = new HashSet<>();
        if (k == Kind.LIKES) {
            SharedPreferences fp = prefs();
            if (fp != null && fp.getBoolean("likes_from_file", false)) fromFile = true;
        }
        markRunning(k, true);
        stopAsked = false;
        List<String> setAsideNow = new ArrayList<>();
        if (k == Kind.LIKES && retryingGone) {
            retryingGone = false;
            saveGone(new ArrayList<>(), true);     // this run decides which stay set aside
        }
        int total = target;
        int removed = 0, failed = 0;
        boolean usePost = k.post, triedPost = k.post;
        Map<String, Integer> tries = new HashMap<>();
        Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        Set<String> flying = new java.util.LinkedHashSet<>();
        java.util.concurrent.ExecutorCompletionService<Answer> answers = new java.util.concurrent.ExecutorCompletionService<>(POOL);
        int width = WIDTH[speed()];
        int ceiling = MAX_WIDTH[speed()];
        int yesRun = 0, noRun = 0, pauses = 0, yesSincePause = 0, pausesWithoutYes = 0;
        long holdUntil = 0, offlineSince = 0, lastSave = System.currentTimeMillis();
        long replyMs = 0;
        int replies = 0;
        long startedAt = System.currentTimeMillis();
        boolean dried = false;
        List<String> firstDone = new ArrayList<>();
        boolean checked = k != Kind.LIKES || fromFile;
        Utils.showToastLong("Removing " + fmt(total) + " " + k.noun + "… (progress in your notifications)");
        String title = "Removing " + k.noun;
        ToolNotice.note(title, "Starting: 0 of " + fmt(total), 0, total);
        while (true) {
            if (stopAsked) {
                stopAsked = false;
                savePending(k, pendingNow(flying, queue));
                saveTarget(k, target - removed);
                markRunning(k, false);
                if (!setAsideNow.isEmpty()) saveGone(setAsideNow, false);
                ended(k, "Stopped after " + fmt(removed) + ", as you asked. Long-press Posts and choose Continue to carry on.");
                return;
            }
            long now = System.currentTimeMillis();
            int room = k.daily - doneToday(k) - flying.size();   // today's limit; it carries on after midnight
            if (now >= holdUntil) {
                while (flying.size() < width && !queue.isEmpty() && removed + flying.size() < target && room-- > 0) {
                    final String id = queue.poll();
                    if (done.contains(id) || flying.contains(id)) continue;
                    flying.add(id);
                    final String url = k.url(id);
                    final boolean p = usePost;
                    answers.submit(() -> ask(id, url, p));
                }
            }
            if (flying.isEmpty()) {
                if (removed >= target) break;
                if (room <= 0 && !queue.isEmpty()) {
                    savePending(k, pendingNow(flying, queue));
                    saveTarget(k, target - removed);
                    ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · today's " + fmt(k.daily)
                            + " done, carrying on after midnight", removed, total);
                    sleep(60_000);
                    continue;
                }
                if (now < holdUntil) {
                    sleep(Math.min(1000, holdUntil - now));
                    continue;
                }
                if (queue.isEmpty()) {
                    if (k != Kind.LIKES || fromFile) break;
                    List<String> more = refillLikes(done, target - removed, removed, total, title);
                    if (more.isEmpty()) {
                        dried = true;
                        break;
                    }
                    queue.addAll(more);
                }
                continue;
            }
            Answer a;
            try {
                java.util.concurrent.Future<Answer> f = answers.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (f == null) continue;
                a = f.get();
            } catch (Throwable e) {
                Cleancut.favLog("engine: " + e);
                continue;
            }
            flying.remove(a.id);
            now = System.currentTimeMillis();

            if (a.error != null) {
                // No answer at all (no signal, the app paused): not TikTok saying no. Ask again later.
                queue.addFirst(a.id);
                if (offlineSince == 0) {
                    offlineSince = now;
                    Cleancut.favLog("no answer, waiting: " + a.error);
                }
                width = Math.max(1, Math.min(width, 2));
                if (now >= holdUntil) {
                    holdUntil = now + 10_000;
                    boolean away = String.valueOf(a.error.getMessage()).contains("background");
                    String wait = away ? "Paused: open the TT test again to carry on" : "No connection, waiting for it to come back…";
                    ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · " + wait, removed, total);
                }
                if (now - lastSave > 5000) {
                    savePending(k, pendingNow(flying, queue));     // keeps waiting, with its place kept
                    lastSave = now;
                }
                continue;
            }
            offlineSince = 0;
            replyMs += a.ms;
            replies++;

            int code = a.code;
            String msg = a.msg;
            if (code != 0 && !triedPost) {
                // Not the way that worked so far: try the other (GET/POST) once, keep what works.
                triedPost = true;
                Cleancut.favLog(k.noun + (usePost ? " POST " : " GET ") + a.id + ": " + a.raw);
                Answer b = ask(a.id, k.url(a.id), !usePost);
                Cleancut.favLog(k.noun + (usePost ? " GET " : " POST ") + a.id + ": " + (b.error != null ? b.error : b.raw));
                if (b.error == null && b.code == 0) {
                    usePost = !usePost;
                    code = 0;
                }
            }

            if (code == 0) {
                done.add(a.id);
                removed++;
                countToday(k);
                noRun = 0;
                yesRun++;
                yesSincePause++;
                pausesWithoutYes = 0;
                if (yesRun % 40 == 0) pauses = Math.max(0, pauses - 1);
                if (yesRun % 10 == 0 && width < ceiling) width++;                       // quicker while it's all yes
                if (yesRun % 300 == 0 && ceiling < MAX_WIDTH[speed()]) ceiling++;     // and try a notch higher again
                if (firstDone.size() < 3) firstDone.add(a.id);
                if (!checked && firstDone.size() == 3) {
                    // TikTok said yes three times: check the three really left your Liked list.
                    checked = true;
                    String stuck = stillLiked(firstDone);
                    if (stuck != null) {
                        savePending(k, pendingNow(flying, queue));
                        saveTarget(k, target - removed);
                        Cleancut.favLog("likes said removed but still in the Liked list: " + stuck);
                        markRunning(k, false);
                        if (!setAsideNow.isEmpty()) saveGone(setAsideNow, false);
                        ended(k, "Stopped: TikTok said yes but those likes are still there. Nothing else was "
                                + "touched; the details are in cleancut-favourites.txt for Claude.");
                        return;
                    }
                }
                if (removed % 50 == 0 || now - lastSave > 5000) {
                    savePending(k, pendingNow(flying, queue));
                    if (!setAsideNow.isEmpty()) {
                        saveGone(setAsideNow, false);
                        setAsideNow.clear();
                    }
                    lastSave = now;
                }
                long ran = now - startedAt;
                ToolNotice.progress(title, fmt(removed) + " of " + fmt(total)
                        + (ran > 15_000 ? " · " + Math.round(removed * 60000.0 / ran) + "/min" : "")
                        + (flying.size() + 1 > 1 ? " · " + width + " at once" : "")
                        + (replies > 0 ? String.format(Locale.ROOT, " · reply %.1fs", replyMs / 1000.0 / replies) : "")
                        + (failed > 0 ? " · " + fmt(failed) + " skipped" : ""), removed, total);
                if (replies >= 200) {    // a moving average
                    replyMs = replyMs / 2;
                    replies = replies / 2;
                }
                long gap = every(k);
                if (gap > 0) holdUntil = Math.max(holdUntil, now + gap + RANDOM.nextInt((int) (gap / 3 + 50)));
                continue;
            }

            // TikTok said no.
            String why = msg.isEmpty() ? "status " + code : msg;
            noRun++;
            yesRun = 0;
            boolean gone = aboutTheVideo(msg);
            if (gone) noRun = Math.max(0, noRun - 1);        // a deleted video says nothing about pace
            boolean slow = !gone && (slowDown(msg) || noRun >= 6);
            if (slow) {
                queue.addFirst(a.id);                     // not this video's fault: ask again after the pause
                if (now >= holdUntil) {
                    if (yesSincePause == 0) pausesWithoutYes++;
                    yesSincePause = 0;
                    pauses++;
                    ceiling = Math.max(1, Math.min(ceiling, width - 1));
                    width = Math.max(1, width / 2);
                    long[] waits = {3_000, 10_000, 30_000, 60_000, 120_000, 300_000};
                    long wait = waits[Math.min(pauses - 1, waits.length - 1)];
                    holdUntil = now + wait;
                    Cleancut.favLog("slow down (" + why + ") at " + (width * 2) + " at once, pausing " + (wait / 1000) + "s");
                    ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · TikTok asked to slow down, pausing "
                            + (wait / 1000) + "s…", removed, total);
                    savePending(k, pendingNow(flying, queue));      // it keeps going after the pause, whatever happens
                }
                continue;
            }
            // A "no" about this one video: try it once more near the end, then set it aside.
            int t = tries.containsKey(a.id) ? tries.get(a.id) + 1 : 1;
            tries.put(a.id, t);
            if (t < 2 && !gone) {
                queue.addLast(a.id);
            } else {
                failed++;
                done.add(a.id);
                if (k == Kind.LIKES) setAsideNow.add(a.id);
                Integer c = reasons.get(why);
                reasons.put(why, c == null ? 1 : c + 1);
                if (c == null) Cleancut.favLog("set aside " + k.noun + " " + a.id + ": " + why);
            }
        }
        StringBuilder skippedNote = new StringBuilder();
        if (failed > 0) {
            skippedNote.append(' ').append(fmt(failed)).append(" couldn't be removed (");
            int n = 0;
            for (Map.Entry<String, Integer> e : reasons.entrySet()) {
                if (n++ == 3) break;
                if (n > 1) skippedNote.append("; ");
                skippedNote.append(e.getKey()).append(": ").append(fmt(e.getValue()));
            }
            skippedNote.append(").");
            Cleancut.favLog(k.noun + " set aside: " + reasons);
        }
        long ran = Math.max(1, System.currentTimeMillis() - startedAt);
        Cleancut.favLog(k.noun + " run: " + removed + " removed in " + (ran / 1000) + "s, " + failed + " set aside, "
                + pauses + " pauses, ended at " + width + " at once");
        if (dried && removed < target) {
            // TikTok isn't listing any more right now: keep the place, don't call it done.
            savePending(k, pendingNow(flying, queue));
            saveTarget(k, target - removed);
            long now = likesNow();
            markRunning(k, false);
            if (!setAsideNow.isEmpty()) saveGone(setAsideNow, false);
            ended(k, "Removed " + fmt(removed) + " " + k.noun + " so far. TikTok isn't listing more of them right now"
                    + (now >= 0 ? " (it says you have " + fmt(now) + " likes)" : "")
                    + ": long-press Posts in a few minutes and choose Continue." + skippedNote);
            return;
        }
        savePending(k, new ArrayList<>());
        saveTarget(k, 0);
        long now = k == Kind.LIKES ? likesNow() : -1;
        markRunning(k, false);
        if (!setAsideNow.isEmpty()) saveGone(setAsideNow, false);
        ended(k, "Done: removed " + fmt(removed) + " " + k.noun + " in " + Math.max(1, Math.round(ran / 60000.0)) + " min."
                + skippedNote
                + (now >= 0 ? " You now have " + fmt(now) + " likes." : " Open the tab again to see the new count."));
    }

    private static String minutes(Kind k, int n) {
        long m = Math.max(1, Math.round(n * (every(k) + every(k) / 6.0 + (every(k) == 0 ? 0 : 50) + 400) / 60000.0));
        return m >= 90 ? (Math.round(m / 6.0) / 10.0) + " hours" : m + (m == 1 ? " minute" : " minutes");
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance().format(n);
    }

    private static String get(String url) throws Exception {
        return FavoritesTabs.get(url);
    }

    /** A POST through TikTok's own network stack (parameters in the address, empty form). */
    private static String post(String url) throws Exception {
        Class<?> utils = Class.forName("com.ss.android.common.util.NetworkUtils");
        Method m = utils.getMethod("executePost", int.class, String.class, List.class);
        try {
            return (String) m.invoke(null, 1024 * 1024, url, new ArrayList<>());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Which of these are still at the top of your Liked list (checked twice, a few seconds apart); null if none. */
    private static String stillLiked(List<String> ids) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                sleep(attempt == 0 ? 2500 : 6000);
                FavoritesOrder.Page p = FavoritesOrder.likedPage(0, 30, ownUid, ownSecUid);
                List<String> stuck = new ArrayList<>();
                for (String id : ids) if (p.ids.contains(id)) stuck.add(id);
                if (stuck.size() < ids.size()) return null;      // at least some went: it works
                if (attempt == 1) return android.text.TextUtils.join(",", stuck);
            }
        } catch (Exception e) {
            Cleancut.favLog("likes check: " + e.getMessage());
        }
        return null;     // couldn't check: carry on (each reply was a yes)
    }

    /** A toast now and the notification: for when the run ends. */
    private static void ended(Kind k, String msg) {
        Utils.showToastLong(msg);
        ToolNotice.finish(cap(k.noun) + ": " + (msg.startsWith("Done") ? "done" : "stopped"), msg);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int targetLeft(Kind k) {
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(k.key + "_target", 0);
    }

    private static void saveTarget(Kind k, int n) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(k.key + "_target", Math.max(0, n)).apply();
    }

    /**
     * The newest likes now (the ones just unliked sit at the top, hidden), minus any already done, up to
     * 300 at a time so the top isn't read over and over.
     */
    private static List<String> refillLikes(Set<String> done, int want, int removed, int total, String title) {
        LikesScan s = scanLikes(Math.min(want, 300), done, false, (t, f, w) ->
                ToolNotice.info(title, fmt(removed) + " of " + fmt(total) + " · " + t, removed, total));
        Cleancut.favLog("likes refill: " + s.ids.size() + " found");
        return s.ids;
    }

    // --- the day's limit and rests -------------------------------------------------------------

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static int doneToday(Kind k) {
        SharedPreferences p = prefs();
        if (p == null) return 0;
        return today().equals(p.getString(k.key + "_day", "")) ? p.getInt(k.key + "_today", 0) : 0;
    }

    private static void countToday(Kind k) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString(k.key + "_day", today()).putInt(k.key + "_today", doneToday(k) + 1).apply();
    }


    /**
     * TikTok's own "remove unavailable videos" (its banner's request: a form POST of one field,
     * cursor, the save time it last found unavailable ones at). Asked with 0 and now first, then
     * from the start of every month back to 2016, a second apart, adding up what TikTok removed.
     */
    private static void removeUnavailable() {
        work("Removing unavailable favourite videos (about 2 minutes)…", () -> {
            List<Long> cursors = new ArrayList<>();
            cursors.add(0L);
            cursors.add(System.currentTimeMillis() / 1000);
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            while (c.get(Calendar.YEAR) >= 2016) {
                cursors.add(c.getTimeInMillis() / 1000);
                c.add(Calendar.MONTH, -1);
            }
            Set<String> gone = new HashSet<>();
            int asked = 0, refused = 0;
            for (long cursor : cursors) {
                String raw = postForm(HOST + "/aweme/v1/aweme/collect/removeinvalid", "cursor=" + cursor);
                asked++;
                JSONObject r = new JSONObject(raw);
                if (r.optInt("status_code", -1) != 0) {
                    refused++;
                    Cleancut.favLog("removeinvalid " + cursor + ": " + raw);
                    if (refused >= 5) break;
                } else {
                    JSONObject extra = r.optJSONObject("extra");
                    JSONArray ids = extra == null ? null : extra.optJSONArray("fatal_item_ids");
                    if (ids == null) ids = r.optJSONArray("fatal_item_ids");
                    if (ids == null) ids = r.optJSONArray("invalid_item_ids");
                    if (ids != null) for (int i = 0; i < ids.length(); i++) gone.add(String.valueOf(ids.opt(i)));
                    if (asked <= 3 || ids != null && ids.length() > 0) Cleancut.favLog("removeinvalid " + cursor + ": " + raw);
                }
                if (asked % 30 == 0) Utils.showToastShort("Checking for unavailable videos… " + (asked * 100 / cursors.size()) + "%");
                ToolNotice.progress("Removing unavailable favourites", "Checking month " + asked + " of " + cursors.size(),
                        asked, cursors.size());
                sleep(900 + RANDOM.nextInt(400));
            }
            int n = gone.size();
            String endMsg = refused >= 5
                    ? "TikTok stopped answering this (removed " + fmt(n) + " so far); try again later."
                    : "Done. Close and reopen Favourites to see the new count"
                    + (n > 0 ? " (TikTok reported " + fmt(n) + " removed)." : ".");
            ToolNotice.finish("Unavailable favourites: " + (refused >= 5 ? "stopped" : "done"), endMsg);
            Utils.showToastLong(refused >= 5
                    ? "TikTok stopped answering this (removed " + fmt(n) + " so far); try again later."
                    : "Done. Close and reopen Favourites to see the new count"
                    + (n > 0 ? " (TikTok reported " + fmt(n) + " removed)." : "."));
        });
    }

    /** A POST with a form body (field=value&...), through TikTok's own network stack. */
    private static String postForm(String url, String form) throws Exception {
        Class<?> utils = Class.forName("com.ss.android.common.util.NetworkUtils");
        for (Method m : utils.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("executePost") || p.length != 5 || p[0] != int.class || p[1] != String.class
                    || p[2] != byte[].class || p[4] != String.class) continue;
            try {
                // No compression (null), as a normal form.
                return (String) m.invoke(null, 1024 * 1024, url, form.getBytes("UTF-8"), null,
                        "application/x-www-form-urlencoded");
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw e;
            }
        }
        // Not found on this version: the parameters in the address instead.
        return post(url + (url.contains("?") ? "&" : "?") + form);
    }

    /**
     * The favourites TikTok counts but won't show (deleted or private videos): its replies to the
     * Favourites request carry their ids on the side (invalidItemIdList), for its banner. The whole
     * list is read, newest to oldest, those ids gathered, and they're removed one at a time like
     * any other favourite, as with the hidden sounds and effects.
     */
    private static void flushHidden() {
        work("Looking through all your favourites for hidden ones…", () -> {
            knowMe();
            Set<String> hidden = new java.util.LinkedHashSet<>();
            Set<String> shown = new java.util.LinkedHashSet<>();
            long cursor = 0;
            int pages = 0;
            String info = "";
            for (; pages < 400; pages++) {
                FavoritesOrder.Page p = page(cursor, 30);
                hidden.addAll(p.invalid);
                shown.addAll(p.ids);
                if (pages < 3 || !p.invalid.isEmpty()) {
                    Cleancut.favLog("flush page " + pages + " at " + cursor + ": " + p.ids.size() + " shown, "
                            + p.invalid.size() + " hidden, counts " + p.invalidInfo + ", more " + p.more + ", next " + p.next);
                }
                info = p.invalidInfo;
                if (!p.more || p.next <= 0 || p.next == cursor) break;
                cursor = p.next;
                ToolNotice.scan("Looking through favourites", fmt(shown.size() + hidden.size()) + " seen, "
                        + fmt(hidden.size()) + " hidden · page " + (pages + 1), shown.size() + hidden.size(), 0);
                if (pages % 10 == 9) Utils.showToastShort("Looking through favourites… " + fmt(shown.size() + hidden.size()) + " so far");
            }
            hidden.removeAll(shown);
            List<String> ids = new ArrayList<>(hidden);
            ids.addAll(shown);       // any still showing go too: this is for clearing out the very last
            Cleancut.favLog("flush: " + hidden.size() + " hidden, " + shown.size() + " shown, after " + (pages + 1) + " pages");
            final String last = info;
            ToolNotice.clear();
            Utils.runOnMainThread(() -> {
                if (ids.isEmpty()) {
                    Activity activity = Utils.getVisibleActivity();
                    if (activity == null) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("No hidden favourites handed over")
                            .setMessage("TikTok's replies didn't include the hidden ones this time (its counts: " + last
                                    + "). The details are in cleancut-favourites.txt for Claude.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                confirmRemove(Kind.FAVOURITES, ids,
                        "Remove " + fmt(ids.size()) + " favourites (" + fmt(hidden.size()) + " hidden, " + fmt(shown.size()) + " showing)?",
                        plan(Kind.FAVOURITES, ids.size()));
            });
        });
    }

    // --- liked videos -------------------------------------------------------------------------

    private static void countLiked() {
        work("Counting your liked videos…", () -> {
            JSONObject json = new JSONObject(get(HOST + "/aweme/v1/user/profile/self/"));
            JSONObject user = json.optJSONObject("user");
            if (user == null) throw new Exception("no profile in TikTok's reply");
            long liked = user.optLong("favoriting_count", -1);
            StringBuilder counts = new StringBuilder();
            for (java.util.Iterator<String> it = user.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.endsWith("_count") || k.startsWith("total_")) counts.append(k).append('=').append(user.opt(k)).append(' ');
            }
            Cleancut.favLog("profile counts: " + counts);
            int likesToday = doneToday(Kind.LIKES), favsToday = doneToday(Kind.FAVOURITES);
            Utils.runOnMainThread(() -> {
                Activity activity = Utils.getVisibleActivity();
                if (activity == null) return;
                new AlertDialog.Builder(activity)
                        .setTitle("Liked videos")
                        .setMessage((liked >= 0
                                ? "You've liked " + fmt(liked) + " videos."
                                : "TikTok didn't send the count this time.")
                                + "\n\nRemoved today: " + fmt(likesToday) + " of " + fmt(Kind.LIKES.daily) + " likes, "
                                + fmt(favsToday) + " of " + fmt(Kind.FAVOURITES.daily) + " favourites.")
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    // --- helpers ------------------------------------------------------------------------------

    private interface Job {
        void run() throws Exception;
    }

    private static void work(String toast, Job job) {
        if (busy) {
            Utils.showToastShort("Still working on the last one…");
            return;
        }
        busy = true;
        if (toast != null) Utils.showToastLong(toast);
        keepScreenOn(true);      // TikTok drops its requests when the screen goes off
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (Throwable e) {
                Cleancut.favLog("favourites tools: " + e);
                ToolNotice.clear();
                Utils.showToastLong("Couldn't do that: " + e.getMessage());
            } finally {
                busy = false;
                keepScreenOn(false);
            }
        }, "cleancut-tools");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> pending(Kind k) {
        SharedPreferences p = prefs();
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String s : p.getString(k.key, "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static void savePending(Kind k, List<String> ids) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(k.key, android.text.TextUtils.join(",", ids)).apply();
    }

    private static SharedPreferences prefs() {
        Context c = Utils.getContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long startOfYear(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        return c.getTimeInMillis() / 1000;
    }
}
