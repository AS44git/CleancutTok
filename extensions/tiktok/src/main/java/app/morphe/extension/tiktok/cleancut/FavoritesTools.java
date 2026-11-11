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
    /** How many likes the chosen run is for (the list is read again from the top as they go). */
    private static volatile int likesWanted;
    private static final Random RANDOM = new Random();

    /** What a run removes, and how gently. */
    private enum Kind {
        FAVOURITES("favourites", "remove_pending", 1500, 1200, false),
        LIKES("likes", "likes_pending", 2000, 1600, true);

        final String noun;
        final String key;
        /** At most this many a day. */
        final int daily;
        /** Milliseconds between removals (plus up to 0.6 s at random). */
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
        labels.add("Liked: remove my most recent likes…");
        actions.add(FavoritesTools::pickLikes);
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
        String[] choices = {"100", "250", "500", "1,000 (a day's limit)"};
        int[] amounts = {100, 250, 500, 1000};
        new AlertDialog.Builder(activity)
                .setTitle("Remove my most recent likes…")
                .setItems(choices, (d, which) -> {
                    int n = amounts[which];
                    likesWanted = n;
                    work("Finding your " + fmt(n) + " most recent likes…", () -> {
                        List<String> ids = recentLikes(n);
                        Utils.runOnMainThread(() -> confirmRemove(Kind.LIKES, ids,
                                "Unlike your " + fmt(Math.max(ids.size(), n)) + " most recent liked videos?",
                                "Newest first (TikTok only lists your recent likes; older ones show up as these go). "
                                        + plan(Kind.LIKES, ids.size())));
                    });
                })
                .show();
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

    /** Your n most recent likes, newest first, through TikTok's own Liked tab request. */
    private static List<String> recentLikes(int n) throws Exception {
        knowMe();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int empty = 0;
        for (int page = 0; page < 300 && out.size() < n; page++) {
            FavoritesOrder.Page p = FavoritesOrder.likedPage(cursor, 30, ownUid, ownSecUid);
            sleep(300);
            int added = 0;
            for (String id : p.ids) if (seen.add(id)) { out.add(id); added++; }
            // Empty pages (likes just removed, hidden) are skipped past while TikTok says there's more.
            empty = added == 0 ? empty + 1 : 0;
            if (!p.more || p.next <= 0 || p.next == cursor || empty >= 120) break;
            cursor = p.next;
            if (page % 10 == 9) Utils.showToastShort("Finding likes… " + fmt(out.size()) + " so far");
        }
        Cleancut.favLog("found " + out.size() + " likes (pages read up to cursor " + cursor + ")");
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
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
        if (activity == null) return;
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

    private static void removeAllOn(Kind k) {
        List<String> left = new ArrayList<>(pending(k));
        // Likes: the target count; as the found ones go, the Liked list's top is read again.
        int target = k == Kind.LIKES ? Math.max(left.size(), targetLeft(k)) : left.size();
        Set<String> done = new HashSet<>();
        int allowance = k.daily - doneToday(k);
        if (allowance <= 0) {
            ended(k, "Today's limit of " + fmt(k.daily) + " " + k.noun + " is reached. "
                    + fmt(left.size()) + " left: long-press Posts tomorrow to continue.");
            return;
        }
        int total = Math.min(target, allowance);
        int removed = 0;
        int inARow = 0;
        boolean usePost = k.post, triedPost = k.post;
        Map<String, Integer> tries = new HashMap<>();
        int noAnswer = 0;
        List<String> firstDone = new ArrayList<>();
        boolean checked = k != Kind.LIKES;
        Utils.showToastLong("Removing " + fmt(total) + " " + k.noun + "… (progress in your notifications)");
        String title = "Removing " + k.noun;
        ToolNotice.note(title, "Starting: 0 of " + fmt(total), 0, total);
        while (true) {
            if (left.isEmpty()) {
                if (k != Kind.LIKES || removed >= target) break;
                List<String> more = refillLikes(done, target - removed);
                if (more.isEmpty()) break;
                left.addAll(more);
            }
            if (removed >= target) break;
            if (removed >= allowance) {
                savePending(k, left);
                saveTarget(k, target - removed);
                ended(k, "Removed " + fmt(removed) + " " + k.noun + ": today's limit. "
                        + fmt(k == Kind.LIKES ? target - removed : left.size()) + " left: long-press Posts tomorrow to continue.");
                return;
            }
            String id = left.get(0);
            String why;
            boolean offline = false;
            try {
                String url = k.url(id);
                String raw = usePost ? post(url) : get(url);
                JSONObject r;
                try {
                    r = new JSONObject(raw);
                } catch (Exception notJson) {
                    throw new java.io.IOException("not TikTok's answer (" + String.valueOf(raw).length() + " chars)");
                }
                int code = r.optInt("status_code", -1);
                if (code != 0 && !triedPost) {
                    // Not the way that worked so far: try the other (GET/POST) once, keep what works.
                    triedPost = true;
                    Cleancut.favLog(k.noun + (usePost ? " POST " : " GET ") + id + ": " + raw);
                    try {
                        String raw2 = usePost ? get(url) : post(url);
                        Cleancut.favLog(k.noun + (usePost ? " GET " : " POST ") + id + ": " + raw2);
                        JSONObject r2 = new JSONObject(raw2);
                        if (r2.optInt("status_code", -1) == 0) { usePost = !usePost; r = r2; code = 0; }
                    } catch (Throwable t) {
                        Cleancut.favLog(k.noun + " POST " + id + ": " + t);
                    }
                }
                String msg = r.optString("status_msg", "");
                why = code == 0 ? null : (msg.isEmpty() ? "status " + code : msg);
            } catch (Throwable t) {
                // No answer at all (no signal, screen off, the app paused): not TikTok saying no.
                why = String.valueOf(t.getMessage());
                offline = true;
            }
            if (offline) {
                if (++noAnswer == 1) Cleancut.favLog("no answer, waiting: " + why);
                if (noAnswer % 6 == 1) {
                    boolean away = String.valueOf(why).contains("background");
                    String wait = away ? "Paused: open the TT test again to carry on" : "No connection, waiting for it to come back…";
                    Utils.showToastShort(wait);
                    ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · " + wait, removed, total);
                }
                if (noAnswer >= 180) {     // half an hour with no answer: stop, keep the place
                    savePending(k, left);
                    saveTarget(k, target - removed);
                    ended(k, "Stopped after " + fmt(removed) + ": no connection for half an hour. "
                            + "Long-press Posts to continue.");
                    return;
                }
                sleep(10_000);
                continue;
            }
            noAnswer = 0;
            if (why == null) {
                left.remove(0);
                done.add(id);
                removed++;
                inARow = 0;
                countToday(k);
                if (firstDone.size() < 3) firstDone.add(id);
                if (!checked && firstDone.size() == 3) {
                    // TikTok said yes three times: check the three really left your Liked list.
                    checked = true;
                    String stuck = stillLiked(firstDone);
                    if (stuck != null) {
                        savePending(k, left);
                        saveTarget(k, target - removed);
                        Cleancut.favLog("likes said removed but still in the Liked list: " + stuck);
                        ended(k, "Stopped: TikTok said yes but those likes are still there. Nothing else was "
                                + "touched; the details are in cleancut-favourites.txt for Claude.");
                        return;
                    }
                }
                if (removed % 10 == 0) savePending(k, left);
                if (removed % 50 == 0) Utils.showToastShort("Removed " + fmt(removed) + " of " + fmt(total) + " " + k.noun + "…");
                ToolNotice.progress(title, fmt(removed) + " of " + fmt(total) + " · about "
                        + minutes(k, Math.max(0, total - removed)) + " left", removed, total);
                sleep(k.every + RANDOM.nextInt(600));
                continue;
            }
            Cleancut.favLog("remove " + k.noun + " " + id + ": " + why);
            inARow++;
            left.remove(0);
            int t = tries.containsKey(id) ? tries.get(id) + 1 : 1;
            tries.put(id, t);
            if (t < 3) left.add(id);          // later in the run, once more
            else done.add(id);
            if (inARow >= 6 && removed == 0) {
                // Refused from the very first one: not TikTok slowing things down, something's wrong.
                savePending(k, left);
                saveTarget(k, target);
                ended(k, "TikTok refused every one (" + why + "), so nothing was removed. "
                        + "The details are in cleancut-favourites.txt for Claude.");
                return;
            } else if (inARow >= 9) {
                savePending(k, left);
                saveTarget(k, target - removed);
                ended(k, "Stopped after " + fmt(removed) + " (TikTok: " + why + "). "
                        + "Long-press Posts to continue whenever you like.");
                return;
            } else if (inARow == 6) {
                Utils.showToastLong("TikTok is still saying no, waiting 5 minutes… (" + why + ")");
                ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · TikTok is saying no, waiting 5 minutes…",
                        removed, total);
                sleep(300_000);
            } else if (inARow == 3) {
                Utils.showToastLong("TikTok is slowing things down, waiting a minute… (" + why + ")");
                ToolNotice.note(title, fmt(removed) + " of " + fmt(total) + " · TikTok is slowing things down, waiting a minute…",
                        removed, total);
                sleep(60_000);
            } else {
                sleep(4_000);
            }
        }
        savePending(k, left);
        saveTarget(k, 0);
        ended(k, "Done: removed " + fmt(removed) + " " + k.noun + ". Open the tab again to see the new count.");
    }

    private static String minutes(Kind k, int n) {
        long m = Math.max(1, Math.round(n * (k.every + 300 + 400) / 60000.0));
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

    /** The newest likes now (the ones just unliked are gone from the top), minus any already done. */
    private static List<String> refillLikes(Set<String> done, int want) {
        List<String> out = new ArrayList<>();
        try {
            knowMe();
            long cursor = 0;
            for (int page = 0; page < 120 && out.size() < want; page++) {
                FavoritesOrder.Page p = FavoritesOrder.likedPage(cursor, 30, ownUid, ownSecUid);
                sleep(300);
                for (String id : p.ids) if (!done.contains(id) && !out.contains(id)) out.add(id);
                if (!out.isEmpty() || !p.more || p.next <= 0 || p.next == cursor) break;
                cursor = p.next;    // the top is all done or hidden (TikTok catching up): look further
                if (page % 10 == 9) ToolNotice.progress("Removing likes", "Looking further down your likes…", 0, 0);
            }
        } catch (Exception e) {
            Cleancut.favLog("likes refill: " + e.getMessage());
        }
        return out.size() > want ? new ArrayList<>(out.subList(0, want)) : out;
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
