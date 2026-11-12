/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import app.morphe.extension.shared.Utils;

/**
 * The favourites & likes tools' notification: progress while a run goes (a bar and a line on
 * what it's doing, waits included), then how it ended. One notification, updated in place;
 * tapping it opens the app. Only ever about the tools' own runs.
 */
final class ToolNotice {
    private static final String CHANNEL = "cleancut_tools";
    private static final int ID = 0x0C1EA;
    private static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static long lastUpdate;

    private ToolNotice() {}

    /** Android 13+: ask for notifications (TikTok's own permission) if they're off. */
    static void askPermission(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{PERMISSION}, 0x0C1E);
            }
        } catch (Throwable ignored) {
            // without it there are just the toasts
        }
    }

    /** "Removing likes", "340 of 1,000", with a bar. Updates more often than every 1.5 s are skipped. */
    static void progress(String title, String text, int done, int total) {
        long now = System.currentTimeMillis();
        if (done > 0 && done < total && now - lastUpdate < 1500) return;
        lastUpdate = now;
        post(title, text, done, total, true);
    }

    /** A wait or other news mid-run, keeping the bar where it is. */
    static void note(String title, String text, int done, int total) {
        lastUpdate = System.currentTimeMillis();
        post(title, text, done, total, true);
    }

    /**
     * Finding things (before anything is removed): a bar toward how many are wanted, or a moving
     * one when that isn't known. Updates more often than every 0.8 s are skipped.
     */
    static void scan(String title, String text, int found, int wanted) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 800) return;
        lastUpdate = now;
        post(title, text, found, wanted, true, wanted <= 0);
    }

    /** A line of news during a run (what it's finding between removals): the bar stays, at most one a second. */
    static void info(String title, String text, int done, int total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 1000) return;
        lastUpdate = now;
        post(title, text, done, total, true);
    }

    /** Takes the notification away (a search that ended and is handing over to a dialog). */
    static void clear() {
        try {
            Context c = Utils.getContext();
            if (c == null) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
            // nothing to take away
        }
    }

    /** How the run ended: no bar, can be swiped away. */
    static void finish(String title, String text) {
        post(title, text, 0, 0, false);
    }

    private static void post(String title, String text, int done, int total, boolean ongoing) {
        post(title, text, done, total, ongoing, false);
    }

    private static void post(String title, String text, int done, int total, boolean ongoing, boolean moving) {
        try {
            Context c = Utils.getContext();
            if (c == null) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Favourites & likes tools",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Progress of removing favourites and likes");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(ongoing ? android.R.drawable.stat_notify_sync : android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOnlyAlertOnce(true)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing)
                    .setShowWhen(!ongoing);
            if (ongoing && moving) b.setProgress(0, 0, true);
            else if (ongoing && total > 0) b.setProgress(total, Math.min(done, total), false);
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (open != null) {
                b.setContentIntent(PendingIntent.getActivity(c, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(ID, b.build());
        } catch (Throwable t) {
            Cleancut.favLog("notification: " + t);
        }
    }
}
