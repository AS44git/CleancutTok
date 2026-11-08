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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * Long-press Posts in the Favourites row: tools for favourite videos and liked videos.
 *
 * <ul>
 *   <li>Remove the favourites saved in a year, or the oldest N. Favourites are listed by save
 *       time ({@code /aweme/v1/aweme/listcollection/}, cursor = a save time, newest first), so a
 *       year is the list from 1 January of the next year down to 1 January of that year; the page
 *       that crosses the boundary is walked one video at a time to cut it exactly.</li>
 *   <li>Each is unfavourited with TikTok's request for that, {@code /aweme/v1/aweme/collect/
 *       ?aweme_id=…&action=0}, one every 1.2 s, pausing if TikTok refuses; what's left is kept, so
 *       a cut-off run continues from the menu.</li>
 *   <li>Remove unavailable favourite videos: what TikTok's own banner does,
 *       {@code /aweme/v1/aweme/collect/removeinvalid}.</li>
 *   <li>Count liked videos: {@code favoriting_count} from your own profile
 *       ({@code /aweme/v1/user/profile/self/}); the Liked list itself stops loading long before
 *       the end.</li>
 * </ul>
 * Nothing is removed until what was found has been shown and confirmed.
 */
public final class FavoritesTools {
    private static final String HOST = "https://api16-va.tiktokv.com";
    private static final String PREFS = "cleancut_favorites";
    private static final long REMOVE_EVERY_MS = 1200;
    private static volatile boolean busy;

    private FavoritesTools() {}

    // --- the menu ----------------------------------------------------------------------------

    static void menu() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<String> pending = pending();
        if (!pending.isEmpty()) {
            labels.add("Continue removing (" + fmt(pending.size()) + " left)");
            actions.add(() -> confirmRemove(pending, "Continue removing " + fmt(pending.size()) + " favourites?", null));
        }
        labels.add("Remove favourites from a year…");
        actions.add(FavoritesTools::pickYear);
        labels.add("Remove my oldest favourites…");
        actions.add(FavoritesTools::pickOldest);
        labels.add("Remove unavailable favourite videos");
        actions.add(FavoritesTools::removeUnavailable);
        labels.add("Count my liked videos");
        actions.add(FavoritesTools::countLiked);
        boolean oldest = FavoritesOrder.oldestFirst();
        labels.add(oldest ? "Show favourites newest first" : "Show favourites oldest first");
        actions.add(() -> {
            FavoritesOrder.setOldestFirst(!oldest);
            Utils.showToastLong((oldest ? "Newest" : "Oldest") + " first. Open Favourites again to see it.");
        });
        new AlertDialog.Builder(activity)
                .setTitle("Favourites")
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
                        Utils.runOnMainThread(() -> confirmRemove(ids,
                                "Remove " + fmt(ids.size()) + " favourites saved in " + year + "?",
                                "Oldest first. About " + minutes(ids.size()) + ". Keep TikTok open; if it's cut off, "
                                        + "long-press Posts to continue."));
                    });
                })
                .show();
    }

    private static void pickOldest() {
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        String[] choices = {"100", "250", "500", "1,000"};
        int[] amounts = {100, 250, 500, 1000};
        new AlertDialog.Builder(activity)
                .setTitle("Remove my oldest…")
                .setItems(choices, (d, which) -> {
                    int n = amounts[which];
                    work("Finding your oldest " + fmt(n) + " favourites…", () -> {
                        List<String> ids = oldest(n);
                        Utils.runOnMainThread(() -> confirmRemove(ids,
                                "Remove your oldest " + fmt(ids.size()) + " favourites?",
                                "About " + minutes(ids.size()) + ". Keep TikTok open; if it's cut off, "
                                        + "long-press Posts to continue."));
                    });
                })
                .show();
    }

    // --- listing ------------------------------------------------------------------------------

    /** Ids of favourites saved in [notBefore, before), newest first. */
    private static List<String> listBetween(long before, long notBefore, String what) throws Exception {
        List<String> out = new ArrayList<>();
        long cursor = before;
        for (int page = 0; page < 500; page++) {
            JSONObject json = list(cursor, 30);
            List<String> ids = ids(json);
            long next = json.optLong("cursor", json.optLong("max_cursor", 0));
            boolean more = json.optInt("has_more", 0) == 1;
            if (next >= notBefore || notBefore == 0) {
                out.addAll(ids);
                if (!more || next <= 0 || next >= cursor) break;
                cursor = next;
            } else {
                // This page crosses the start: one video at a time (each reply's cursor is that
                // video's save time) until the save times go past it.
                long c = cursor;
                for (int i = 0; i < 40; i++) {
                    JSONObject one = list(c, 1);
                    List<String> got = ids(one);
                    long t = one.optLong("cursor", one.optLong("max_cursor", 0));
                    if (t < notBefore) break;
                    out.addAll(got);
                    if (one.optInt("has_more", 0) != 1 || t <= 0 || t >= c) break;
                    c = t;
                }
                break;
            }
            if (page % 10 == 9) Utils.showToastShort("Counting favourites " + what + "… " + fmt(out.size()) + " so far");
        }
        return out;
    }

    /** The oldest n favourites, oldest first: a year at a time from the oldest year. */
    private static List<String> oldest(int n) throws Exception {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        int first = -1;
        for (int y = 2014; y <= now; y++) {
            if (!ids(list(startOfYear(y + 1), 1)).isEmpty()) { first = y; break; }
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

    private static JSONObject list(long cursor, int count) throws Exception {
        JSONObject json = new JSONObject(get(HOST + "/aweme/v1/aweme/listcollection/?count=" + count + "&cursor=" + cursor));
        if (json.optInt("status_code", 0) != 0) throw new Exception("TikTok said " + json.optString("status_msg", "status " + json.optInt("status_code")));
        return json;
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

    private static void confirmRemove(List<String> ids, String title, String note) {
        if (ids.isEmpty()) {
            Utils.showToastLong("Nothing to remove there.");
            return;
        }
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setPositiveButton("Remove", (d, w) -> {
                    savePending(ids);
                    work(null, () -> removeAll());
                })
                .setNegativeButton("Cancel", null);
        if (note != null) b.setMessage(note);
        b.show();
    }

    /** Unfavourites what's pending, oldest first, at a steady pace; what's left stays pending. */
    private static void removeAll() {
        List<String> left = new ArrayList<>(pending());
        int total = left.size();
        int removed = 0;
        int refusals = 0;
        Utils.showToastLong("Removing " + fmt(total) + " favourites, oldest first…");
        while (!left.isEmpty()) {
            String id = left.get(0);
            String why;
            try {
                JSONObject r = new JSONObject(get(HOST + "/aweme/v1/aweme/collect/?action=0&aweme_id=" + id));
                int code = r.optInt("status_code", -1);
                why = code == 0 ? null : r.optString("status_msg", "status " + code);
            } catch (Throwable t) {
                why = String.valueOf(t.getMessage());
            }
            if (why == null) {
                left.remove(0);
                removed++;
                refusals = 0;
                if (removed % 10 == 0) savePending(left);
                if (removed % 50 == 0) Utils.showToastShort("Removed " + fmt(removed) + " of " + fmt(total) + "…");
                sleep(REMOVE_EVERY_MS);
                continue;
            }
            Cleancut.favLog("unfavourite " + id + ": " + why);
            refusals++;
            if (refusals == 3) {
                // TikTok is pushing back (too fast, or a problem): wait a minute and try again.
                Utils.showToastLong("TikTok is slowing things down, waiting a minute… (" + why + ")");
                sleep(60_000);
            } else if (refusals >= 6) {
                savePending(left);
                Utils.showToastLong("Stopped after " + fmt(removed) + " (TikTok: " + why + "). "
                        + fmt(left.size()) + " left: long-press Posts later to continue.");
                return;
            } else {
                sleep(3_000);
            }
        }
        savePending(left);
        Utils.showToastLong("Done: removed " + fmt(removed) + " favourites. Open Favourites again to see the new count.");
    }

    private static void removeUnavailable() {
        work("Removing unavailable favourite videos…", () -> {
            String raw = post(HOST + "/aweme/v1/aweme/collect/removeinvalid?cursor=0");
            JSONObject r = new JSONObject(raw);
            int code = r.optInt("status_code", -1);
            Cleancut.favLog("removeinvalid: " + raw);
            Utils.showToastLong(code == 0
                    ? "Done: TikTok removed your unavailable favourite videos. Open Favourites again to see the new count."
                    : "TikTok didn't do it: " + r.optString("status_msg", "status " + code));
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
            Utils.runOnMainThread(() -> {
                Activity activity = Utils.getVisibleActivity();
                if (activity == null) return;
                new AlertDialog.Builder(activity)
                        .setTitle("Liked videos")
                        .setMessage(liked >= 0
                                ? "You've liked " + fmt(liked) + " videos.\n\n(TikTok's Liked list stops loading long before the end; this is the full count from your profile.)"
                                : "TikTok didn't send the count this time.")
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
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (Throwable e) {
                Cleancut.favLog("favourites tools: " + e);
                Utils.showToastLong("Couldn't do that: " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "cleancut-tools");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> pending() {
        SharedPreferences p = prefs();
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String s : p.getString("remove_pending", "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static void savePending(List<String> ids) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString("remove_pending", android.text.TextUtils.join(",", ids)).apply();
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

    private static String minutes(int n) {
        long m = Math.max(1, Math.round(n * (REMOVE_EVERY_MS + 400) / 60000.0));
        return m + (m == 1 ? " minute" : " minutes");
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
}
