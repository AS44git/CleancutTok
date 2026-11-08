/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 */
package app.morphe.extension.tiktok.cleancut;

/** Everything that names this fork, in one place. */
public final class Brand {
    private Brand() {
    }

    public static final String APP_NAME = "CleancutTok";

    /** The repository whose releases the updater checks and downloads from. */
    public static final String GITHUB_REPO = "AS44git/CleancutTok";

    /** Where a problem report goes, and where the source of this build lives (GPLv3). */
    public static final String SOURCE_URL = "https://github.com/" + GITHUB_REPO;
    public static final String NEW_ISSUE_URL = SOURCE_URL + "/issues/new";

    /** The clone build's package suffix, which picks the "-clone.apk" release asset. */
    public static final String CLONE_PACKAGE_SUFFIX = ".cleancuttok";

    /**
     * This fork's own release number on top of TikTok's version: TikTok 47.6.3 at revision 1 is
     * released under the tag v47-6-3-1. Bump it for every release, so a fix on the same TikTok
     * version is still offered as an update. The release workflow reads it from here.
     */
    public static final int REVISION = 2;
}
