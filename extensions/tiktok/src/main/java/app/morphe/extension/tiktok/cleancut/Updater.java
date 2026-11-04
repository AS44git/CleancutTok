/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.extension.tiktok.cleancut;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;

/**
 * Offers new CleancutTok releases: a quiet check of the repository's latest GitHub release a few
 * seconds after TikTok's main screen opens (at most every few hours), a dialog with the release
 * notes, and an in-app download and install through Android's package installer.
 *
 * <p>A release is tagged v{TikTok version with dashes}-{revision}, like v47-6-3-2, and carries
 * two APKs: "...-clone.apk" for the build that installs beside TikTok and "....apk" for the one
 * that replaces it. Both are signed with the same key as this install, so the update installs
 * over it and keeps the account signed in.
 */
public final class Updater {
    private static final String PREFS = "cleancuttok_updater";
    private static final String LAST_CHECK = "last_check";
    private static final String SKIPPED_TAG = "skipped_tag";
    static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final long FIRST_CHECK_DELAY_MS = 4000L;
    private static final String ACTION_INSTALL_STATUS = "app.morphe.extension.tiktok.cleancut.INSTALL_STATUS";

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    /** A release found on GitHub. */
    static final class Release {
        final String tag;
        final String notes;
        final String apkUrl;
        final long apkSize;

        Release(String tag, String notes, String apkUrl, long apkSize) {
            this.tag = tag;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Checking
    // -----------------------------------------------------------------------------------------

    /** Called as TikTok's main activity is created. */
    public static void onMainActivity(Activity activity) {
        if (activity == null) return;
        WeakReference<Activity> ref = new WeakReference<>(activity);
        main.postDelayed(() -> {
            Activity a = ref.get();
            if (a != null) check(a, false);
        }, FIRST_CHECK_DELAY_MS);
    }

    /** Checks now. {@code manual} skips the time limit and the skipped version, and reports "up to date". */
    public static void check(Activity activity, boolean manual) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (!manual && now - prefs.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS) return;
            if (!busy.compareAndSet(false, true)) return;
            prefs.edit().putLong(LAST_CHECK, now).apply();
            Context app = activity.getApplicationContext();
            WeakReference<Activity> ref = new WeakReference<>(activity);
            new Thread(() -> {
                Release release = null;
                Throwable failure = null;
                try {
                    release = latestRelease(isClone(app));
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    busy.set(false);
                }
                Release found = release;
                Throwable failed = failure;
                main.post(() -> {
                    Activity a = ref.get();
                    if (a == null || a.isFinishing()) return;
                    if (failed != null) {
                        Logger.printInfo(() -> "CleancutTok update check failed: " + failed);
                        if (manual) toast(a, "Couldn't check for updates");
                        return;
                    }
                    if (found == null || !isNewer(found.tag, installedVersion(a))) {
                        if (manual) toast(a, Brand.APP_NAME + " is up to date");
                        return;
                    }
                    if (!manual && found.tag.equals(prefs.getString(SKIPPED_TAG, ""))) return;
                    offer(a, found, prefs);
                });
            }, "CleancutTok-update-check").start();
        } catch (Throwable failure) {
            busy.set(false);
            Logger.printException(() -> "CleancutTok update check could not start", failure);
        }
    }

    static boolean isClone(Context context) {
        return context.getPackageName().endsWith(Brand.CLONE_PACKAGE_SUFFIX);
    }

    /** This install's version as release tags count it: TikTok's versionName, then the revision. */
    static List<Integer> installedVersion(Context context) {
        String name;
        try {
            name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable unreadable) {
            name = "0";
        }
        List<Integer> parts = numbers(name == null ? "0" : name);
        parts.add(Brand.REVISION);
        return parts;
    }

    /** Whether a release tag (v47-6-3-2) is newer than an installed version ([47, 6, 3, 1]). */
    static boolean isNewer(String tag, List<Integer> installed) {
        List<Integer> offered = numbers(tag);
        if (offered.isEmpty()) return false;
        for (int i = 0; i < Math.max(offered.size(), installed.size()); i++) {
            int a = i < offered.size() ? offered.get(i) : 0;
            int b = i < installed.size() ? installed.get(i) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /** The runs of digits in a version or a tag, in order. */
    static List<Integer> numbers(String text) {
        List<Integer> out = new ArrayList<>();
        int value = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                value = (value < 0 ? 0 : value * 10) + (c - '0');
                if (value > 1_000_000) value = 1_000_000;
            } else if (value >= 0) {
                out.add(value);
                value = -1;
            }
        }
        if (value >= 0) out.add(value);
        return out;
    }

    private static Release latestRelease(boolean clone) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + Brand.GITHUB_REPO + "/releases/latest").openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "CleancutTok-updater");
        try {
            int code = connection.getResponseCode();
            if (code == 404) return null; // no release yet
            if (code != 200) throw new IllegalStateException("GitHub answered " + code);
            JSONObject json = new JSONObject(read(connection.getInputStream()));
            String tag = json.optString("tag_name", "");
            JSONArray assets = json.optJSONArray("assets");
            if (tag.isEmpty() || assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                boolean cloneAsset = name.endsWith("-clone.apk");
                if (name.endsWith(".apk") && cloneAsset == clone) {
                    return new Release(tag, json.optString("body", ""),
                            asset.optString("browser_download_url", ""), asset.optLong("size", -1));
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Offering and installing
    // -----------------------------------------------------------------------------------------

    private static void offer(Activity activity, Release release, SharedPreferences prefs) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 1500) notes = notes.substring(0, 1500) + "…";
        String size = release.apkSize > 0 ? " (" + (release.apkSize / (1024 * 1024)) + " MB)" : "";
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Brand.APP_NAME + " " + release.tag + " is out")
                .setMessage((notes.isEmpty() ? "A new version is ready." : notes)
                        + "\n\nIt installs over this one and keeps you signed in" + size + ".")
                .setPositiveButton("Update", (d, w) -> download(activity, release))
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) ->
                        prefs.edit().putString(SKIPPED_TAG, release.tag).apply())
                .show();
    }

    private static void download(Activity activity, Release release) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView label = new TextView(activity);
        label.setText("Downloading…");
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(release.apkSize <= 0);
        box.addView(label);
        box.addView(bar);
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Updating " + Brand.APP_NAME)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled.set(true))
                .show();
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            File apk = new File(app.getCacheDir(), "cleancuttok-update.apk");
            try {
                fetch(release, apk, cancelled, fraction -> main.post(() -> {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) (fraction * 1000));
                }));
                if (cancelled.get()) {
                    apk.delete();
                    return;
                }
                main.post(() -> label.setText("Installing…"));
                install(app, apk);
                main.post(dialog::dismiss);
            } catch (Throwable failure) {
                apk.delete();
                Logger.printException(() -> "CleancutTok update download failed", failure);
                main.post(() -> {
                    dialog.dismiss();
                    toast(app, "The update couldn't be downloaded. Try again later.");
                });
            }
        }, "CleancutTok-update-download").start();
    }

    interface Progress {
        void at(double fraction);
    }

    private static void fetch(Release release, File target, AtomicBoolean cancelled, Progress progress)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "CleancutTok-updater");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("download answered " + code);
            long total = connection.getContentLength() > 0 ? connection.getContentLength() : release.apkSize;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastReport = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancelled.get()) return;
                    out.write(buffer, 0, n);
                    done += n;
                    if (total > 0 && done - lastReport > 512 * 1024) {
                        lastReport = done;
                        progress.at(Math.min(1.0, done / (double) total));
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void install(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("cleancuttok", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            registerStatusReceiver(context, apk);
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000; // FLAG_MUTABLE: the installer adds extras
            PendingIntent pending = PendingIntent.getBroadcast(context, id, status, flags);
            session.commit(pending.getIntentSender());
        }
    }

    private static volatile BroadcastReceiver statusReceiver;

    private static void registerStatusReceiver(Context context, File apk) {
        if (statusReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(confirm);
                    }
                    return;
                }
                apk.delete();
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Logger.printInfo(() -> "CleancutTok update install ended: " + status + " " + message);
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        toast(c, "The update didn't install" + (message == null ? "" : ": " + message));
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, 4 /* Context.RECEIVER_NOT_EXPORTED */);
        } else {
            context.registerReceiver(receiver, filter);
        }
        statusReceiver = receiver;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
