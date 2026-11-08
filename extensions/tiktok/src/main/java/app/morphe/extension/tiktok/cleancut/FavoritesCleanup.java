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

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Utils;

/**
 * Removes favourite sounds and effects that are no longer available (blocked in your country,
 * taken down). TikTok counts them but its lists never show them, so they can't be removed by
 * hand. TikTok's own "Remove unavailable" banner only covers videos.
 *
 * <p>Sounds: the favourites list with {@code enable_offline_similar_music=1} (a flag TikTok's
 * own Sounds screen sends) includes the unavailable ones, each with an {@code offline_desc}
 * saying why; each is then unfavourited with TikTok's request for that,
 * {@code /aweme/v1/music/collect/?music_id=…&action=0}. Only the favourited sounds themselves:
 * the "similar" suggestions that come with them ({@code similar_musics}) are never touched.
 *
 * <p>Effects: {@code /aweme/v1/sticker/listcollection/} lists them; an unavailable one comes
 * back with {@code is_favorite} false. It's unfavourited the way TikTok's effect page does it,
 * through the effect platform: {@code IExternalService.abilityService().effectService()
 * .buildEffectPlatform(...)}, then its favourite-list call with the effect's id and false.
 *
 * <p>Everything goes through TikTok's own network stack, and nothing is removed until the list
 * of what was found has been shown and confirmed.
 */
public final class FavoritesCleanup {
    private static final String HOST = "https://api16-va.tiktokv.com";
    private static volatile boolean running;

    private FavoritesCleanup() {}

    private static final class Found {
        final String id;
        final String name;
        final String why;

        Found(String id, String name, String why) {
            this.id = id;
            this.name = name;
            this.why = why;
        }
    }

    // --- entry: long-press on Sounds / Effects ----------------------------------------------

    static void start(String kind) {
        if (running) return;
        running = true;
        Utils.showToastShort("Looking for unavailable favourite " + kind + "…");
        background(() -> {
            try {
                List<Found> found = kind.equals("sounds") ? findSounds() : findEffects();
                Utils.runOnMainThread(() -> confirm(kind, found));
            } catch (Throwable t) {
                Cleancut.favLog("cleanup " + kind + " (find): " + t);
                Utils.showToastLong("Couldn't check favourite " + kind + ": " + t.getMessage());
                running = false;
            }
        });
    }

    private static void confirm(String kind, List<Found> found) {
        if (found.isEmpty()) {
            Utils.showToastLong("No unavailable favourite " + kind + " found.");
            running = false;
            return;
        }
        Activity activity = Utils.getVisibleActivity();
        if (activity == null) {
            running = false;
            return;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < found.size() && i < 12; i++) {
            list.append("• ").append(found.get(i).name).append('\n');
        }
        if (found.size() > 12) list.append("• …and ").append(found.size() - 12).append(" more\n");
        list.append('\n').append(found.get(0).why);
        new AlertDialog.Builder(activity)
                .setTitle("Remove " + found.size() + " unavailable " + kind + "?")
                .setMessage(list.toString())
                .setPositiveButton("Remove", (d, w) -> background(() -> remove(kind, found)))
                .setNegativeButton("Cancel", (d, w) -> running = false)
                .setOnCancelListener(d -> running = false)
                .show();
    }

    private static void remove(String kind, List<Found> found) {
        try {
            int removed = kind.equals("sounds") ? removeSounds(found) : removeEffects(found);
            // Check: what's left unavailable now.
            List<Found> left = kind.equals("sounds") ? findSounds() : findEffects();
            String summary = "Removed " + removed + " of " + found.size() + " unavailable " + kind + "."
                    + (left.isEmpty() ? "" : " " + left.size() + " still listed.")
                    + " The count updates when you reopen Favourites.";
            Cleancut.favLog("cleanup " + kind + ": " + summary);
            Utils.showToastLong(summary);
        } catch (Throwable t) {
            Cleancut.favLog("cleanup " + kind + " (remove): " + t);
            Utils.showToastLong("Couldn't remove favourite " + kind + ": " + t.getMessage());
        } finally {
            running = false;
        }
    }

    // --- sounds ------------------------------------------------------------------------------

    private static List<Found> findSounds() throws Exception {
        List<Found> out = new ArrayList<>();
        long cursor = 0;
        for (int page = 0; page < 30; page++) {
            JSONObject json = new JSONObject(FavoritesTabs.get(HOST
                    + "/aweme/v1/user/music/collect/?count=50&enable_offline_similar_music=1&cursor=" + cursor));
            if (json.optInt("status_code", 0) != 0) throw new Exception("status " + json.optInt("status_code"));
            JSONArray list = json.optJSONArray("mc_list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject m = list.optJSONObject(i);
                    if (m == null) continue;
                    String why = m.optString("offline_desc", "");
                    String id = m.optString("id_str", m.optString("mid", ""));
                    if (!why.isEmpty() && !id.isEmpty()) {
                        String title = m.optString("title", "");
                        String author = m.optString("author", "");
                        out.add(new Found(id, author.isEmpty() ? title : title + " · " + author, why));
                    }
                }
            }
            long next = json.optLong("cursor", 0);
            if (json.optInt("has_more", 0) != 1 || next == 0 || next == cursor) break;
            cursor = next;
        }
        Cleancut.favLog("cleanup sounds: " + out.size() + " unavailable");
        return out;
    }

    private static int removeSounds(List<Found> found) {
        int removed = 0;
        for (int i = 0; i < found.size(); i++) {
            Found f = found.get(i);
            try {
                JSONObject r = new JSONObject(FavoritesTabs.get(HOST
                        + "/aweme/v1/music/collect/?action=0&music_id=" + f.id));
                if (r.optInt("status_code", -1) == 0) removed++;
                else Cleancut.favLog("unfavourite sound " + f.id + ": " + r);
            } catch (Throwable t) {
                Cleancut.favLog("unfavourite sound " + f.id + ": " + t);
            }
            if ((i + 1) % 10 == 0) Utils.showToastShort("Removing sounds… " + (i + 1) + " of " + found.size());
            sleep(300);
        }
        return removed;
    }

    // --- effects -----------------------------------------------------------------------------

    private static List<Found> findEffects() throws Exception {
        List<Found> out = new ArrayList<>();
        long cursor = 0;
        for (int page = 0; page < 10; page++) {
            JSONObject json = new JSONObject(FavoritesTabs.get(HOST
                    + "/aweme/v1/sticker/listcollection/?count=50&cursor=" + cursor));
            if (json.optInt("status_code", 0) != 0) throw new Exception("status " + json.optInt("status_code"));
            JSONArray list = json.optJSONArray("sticker_list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject s = list.optJSONObject(i);
                    if (s == null) continue;
                    // Listed as a favourite, yet the server says it isn't one any more: the
                    // effect is gone (TikTok's effect page says "Unavailable effect").
                    if (!s.optBoolean("is_favorite", true)) {
                        String id = s.optString("effect_id", s.optString("id", ""));
                        if (!id.isEmpty()) {
                            out.add(new Found(id, s.optString("name", id),
                                    "These effects aren't available any more (for example, not in your region)."));
                        }
                    }
                }
            }
            long next = json.optLong("cursor", 0);
            if (json.optInt("has_more", 0) != 1 || next == 0 || next == cursor) break;
            cursor = next;
        }
        Cleancut.favLog("cleanup effects: " + out.size() + " unavailable");
        return out;
    }

    private static int removeEffects(List<Found> found) throws Exception {
        Object platform = effectPlatform();
        if (platform == null) throw new Exception("TikTok's effect platform didn't load");
        Method favorite = null;
        for (Method m : platform.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == String.class && p[1] == List.class && p[2] == boolean.class
                    && p[3] == java.util.Map.class && p[4].getName().endsWith(".IModFavoriteList")) {
                favorite = m;
                break;
            }
        }
        if (favorite == null) throw new Exception("no favourite-list call on the effect platform");
        Class<?> listenerType = favorite.getParameterTypes()[4];
        int removed = 0;
        for (Found f : found) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("no answer");
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
                        if (name.equals("toString")) return "CleancutTok favourite listener";
                        outcome.set(name + (args == null || args.length == 0 ? "" : " " + java.util.Arrays.toString(args)));
                        done.countDown();
                        return null;
                    });
            favorite.invoke(platform, "default", Collections.singletonList(f.id), false, null, listener);
            done.await(20, TimeUnit.SECONDS);
            String o = outcome.get();
            Cleancut.favLog("unfavourite effect " + f.id + ": " + o);
            if (o.toLowerCase().contains("success")) removed++;
        }
        return removed;
    }

    /** TikTok's effect platform, built the way its effect page gets it. */
    private static Object effectPlatform() throws Exception {
        Context context = Utils.getContext();
        Class<?> serviceManager = Class.forName("com.ss.android.ugc.aweme.framework.services.ServiceManager");
        Object manager = serviceManager.getMethod("get").invoke(null);
        Class<?> externalType = Class.forName("com.ss.android.ugc.aweme.services.IExternalService");
        Object external = serviceManager.getMethod("getService", Class.class).invoke(manager, externalType);
        if (external == null) throw new Exception("no IExternalService");
        Object ability = externalType.getMethod("abilityService").invoke(external);
        Object effects = ability.getClass().getMethod("effectService").invoke(ability);
        Class<?> effectType = Class.forName("com.ss.android.ugc.aweme.services.external.ability.IAVEffectService");
        Class<?> readyType = Class.forName(
                "com.ss.android.ugc.aweme.services.external.ability.IAVEffectService$IAVEffectReadyCallback");
        Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Object> platform = new AtomicReference<>();
        Object callback = Proxy.newProxyInstance(readyType.getClassLoader(), new Class<?>[] {readyType},
                (proxy, method, args) -> {
                    if (method.getName().equals("finish")) {
                        platform.set(args == null ? null : args[0]);
                        ready.countDown();
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == (args == null ? null : args[0]);
                    return null;
                });
        Object noConfig = Proxy.newProxyInstance(function1.getClassLoader(), new Class<?>[] {function1},
                (proxy, method, args) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == (args == null ? null : args[0]);
                    return null;
                });
        Method build = effectType.getMethod("buildEffectPlatform", Context.class, readyType, function1);
        Utils.runOnMainThread(() -> {
            try {
                build.invoke(effects, context, callback, noConfig);
            } catch (Throwable t) {
                Cleancut.favLog("effect platform: " + t);
                ready.countDown();
            }
        });
        ready.await(20, TimeUnit.SECONDS);
        return platform.get();
    }

    // --- helpers -----------------------------------------------------------------------------

    private static void background(Runnable r) {
        Thread t = new Thread(r, "cleancut-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
