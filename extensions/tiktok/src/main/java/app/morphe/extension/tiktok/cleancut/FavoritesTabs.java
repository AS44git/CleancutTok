/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;


import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Utils;

/**
 * Long-presses on the Favourites row ("Posts 4531 | Collections 0 | Sounds 53 | Effects 1"):
 * Posts switches newest/oldest first; Sounds and Effects find the unavailable ones TikTok
 * counts but never shows, and remove them once confirmed ({@link FavoritesCleanup}).
 *
 * <p>The row's tabs are found by their text when Favourites asks for a page, so nothing here
 * depends on TikTok's class names. Requests go through TikTok's own NetworkUtils.executeGet,
 * signed like any of the app's.
 */
public final class FavoritesTabs {
    private static final String HOST = "https://api16-va.tiktokv.com";
    private static final Set<View> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());

    private FavoritesTabs() {}

    /** Favourites asked for a page: hook the row once it's drawn. */
    static void attachSoon() {
        Utils.runOnMainThreadDelayed(FavoritesTabs::attach, 700);
        Utils.runOnMainThreadDelayed(FavoritesTabs::attach, 2500);
    }

    private static void attach() {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null) return;
            walk(activity.getWindow().getDecorView());
        } catch (Throwable t) {
            Cleancut.favLog("favourites tabs: " + t);
        }
    }

    private static void walk(View v) {
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null) hook(v, text.toString().trim());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    private static void hook(View label, String text) {
        String kind;
        if (text.matches("Posts \\d[\\d,.]*[KkMm]?")) kind = "posts";
        else if (text.matches("Sounds \\d[\\d,.]*")) kind = "sounds";
        else if (text.matches("Effects \\d[\\d,.]*")) kind = "effects";
        else return;
        // The tab is the clickable parent of the label.
        View tab = label;
        ViewParent p = label.getParent();
        if (p instanceof View && ((View) p).isClickable()) tab = (View) p;
        if (!HOOKED.add(tab)) return;
        final String k = kind;
        tab.setOnLongClickListener(v -> {
            try {
                if (k.equals("posts")) FavoritesTools.menu();
                else FavoritesCleanup.start(k);
            } catch (Throwable t) {
                Cleancut.favLog("favourites long-press: " + t);
            }
            return true;
        });
    }

    // --- Posts: newest / oldest ------------------------------------------------------------

    private static void switchOrder(View posts) {
        boolean oldest = !FavoritesOrder.oldestFirst();
        FavoritesOrder.setOldestFirst(oldest);
        Utils.showToastLong(oldest
                ? "Favourites: oldest first. Open Favourites again to see it."
                : "Favourites: newest first. Open Favourites again to see it.");
    }

    /** A GET through TikTok's own network stack (common parameters and signing included). */
    static String get(String url) throws Exception {
        Class<?> utils = Class.forName("com.ss.android.common.util.NetworkUtils");
        Method m = utils.getMethod("executeGet", int.class, String.class);
        try {
            return (String) m.invoke(null, 8 * 1024 * 1024, url);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
}
