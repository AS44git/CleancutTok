#!/usr/bin/env bash
#
# Fetch the stock Instagram APK the build patches, into the current directory,
# and print the file name it was saved as.
#
#   fetch-stock-apk.sh <source>
#
# <source> is either
#   - a direct https:// link to the .apk/.apkm, or
#   - "release:<name>": the first .apk/.apkm/.xapk/.apks asset of the release
#     called <name> in this repository. Draft releases work too (they are only
#     visible to the repository's collaborators), which is the way to keep the
#     stock APK out of public view: create a draft release named "stock-apk"
#     and attach the APK to it.
#
# Needs GH_TOKEN and GITHUB_REPOSITORY (both set in GitHub Actions).
set -euo pipefail

source_spec="${1:?usage: fetch-stock-apk.sh <url | release:NAME>}"

ext_for() {
    case "$1" in
        *.apkm*) echo apkm ;;
        *.xapk*) echo xapk ;;
        *.apks*) echo apks ;;
        *) echo apk ;;
    esac
}

case "$source_spec" in
    release:*)
        name="${source_spec#release:}"
        # /releases (unlike /releases/tags/...) also lists drafts for a token
        # with push access.
        asset="$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
            --jq ".[] | select(.name == \"$name\" or .tag_name == \"$name\") | .assets[]
                  | select(.name | test(\"\\\\.(apk|apkm|xapk|apks)$\")) | \"\\(.id) \\(.name)\"" \
            | head -1)"
        if [ -z "$asset" ]; then
            echo "::error::No .apk/.apkm asset found on a release named '$name'." >&2
            exit 1
        fi
        id="${asset%% *}"
        file="instagram.$(ext_for "${asset#* }")"
        gh api -H "Accept: application/octet-stream" \
            "repos/$GITHUB_REPOSITORY/releases/assets/$id" > "$file"
        ;;
    https://*|http://*)
        file="instagram.$(ext_for "$source_spec")"
        curl -fL --retry 3 -o "$file" "$source_spec"
        ;;
    *)
        echo "::error::Unknown APK source '$source_spec' (use a https:// link or release:NAME)." >&2
        exit 1
        ;;
esac

if [ ! -s "$file" ]; then
    echo "::error::Downloaded APK is empty." >&2
    exit 1
fi
ls -l "$file" >&2
echo "$file"
