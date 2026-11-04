# CleancutTok

TikTok with only Inbox and Profile: no For You, Following, Friends, LIVE,
Explore, Shop or suggested accounts, opening on Inbox, with a permanent lock.

CleancutTok is a fork of [Hushfeed](https://github.com/SysAdminDoc/hushfeed),
a GPLv3 set of Morphe patches for TikTok. Hushfeed's own README.md is kept
unchanged for now; this file describes the fork.

## Status

First CleancutTok build, untested on a phone. The "CleancutTok" patch
(`patches/.../tiktok/cleancut/`, extension `extensions/tiktok/.../cleancut/Cleancut.java`)
adds four layers:

1. **Bottom bar:** Inbox and Profile only; every start (icon, notification,
   link) that would land on a feed lands on Inbox.
2. **Network gate:** feed and recommendation requests (For You, Following,
   Friends, Explore, Nearby, LIVE feeds, Shop home, suggested accounts) fail
   before they leave the phone, at `SsHttpCall.getResponseWithInterceptorChain`.
3. **One video per page:** a video opened from anywhere but a chat or a
   profile plays on its own, using TikTok's own single-video loader. Chat
   videos still swipe through the other videos sent in that chat.
4. **Permanent lock:** the Hushfeed settings this rests on (bottom tabs, start
   page, feed lock, suggested accounts, LIVE, Shop) are locked in code
   (`Setting.lockTo`). No switch, pause, backup or reset moves them.

How TikTok picks a video page's list is mapped in
[notes/internals-detail-pages.md](notes/internals-detail-pages.md).

## Builds

- **Debug build** (Actions tab): builds Hushfeed's patch bundle, then patches
  the stock TikTok APK attached to the draft release `stock-apk` into a clone
  (package `com.zhiliaoapp.musically.cleancuttok`, named "Tiktok") that
  installs next to any other TikTok. Signed with a throwaway key, so uninstall
  the previous debug build before installing a new one.

Target: TikTok 47.6.3 (version code 2024706030), the nodpi APK from APKMirror.

## License

GPLv3, like Hushfeed. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
