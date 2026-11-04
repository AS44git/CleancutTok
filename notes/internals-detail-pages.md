# TikTok 47.6.3: how a video page gets its list

Mapped by decompiling com.zhiliaoapp.musically 47.6.3 (vc 2024706030).
Obfuscated names (X.0zO4, LIZ...) change with every TikTok release; the
strings and unobfuscated names noted here are what fingerprints should use.

## One factory picks every video page's data source

Every swipeable video page outside the home feed (DetailActivity /
DetailPageFragment / DetailFragmentPanel, including DM videos through
IMDetailPageComponent) gets its list from an "operator", an object
implementing the interface X.0Elv:

    bindView, cannotLoadLatest, cannotLoadMore, deleteItem, getPageType,
    getViewModel, init, isDataEmpty, isLoading, request(int, param, int, bool), unInit

The operator is chosen in one static method:

    X.0zO4.LIZ(X.04hO param, X.07PH, JediViewModel, Fragment) -> X.0Elv

It switches on `param.getFrom()` (getFrom, getAid, getEventType,
getConversationId stay unobfuscated). Fingerprint strings in that method:
"[UserCard]_DETAIL", "from_inbox_detail", "from_notification_page",
"cloud_game_list", "mp_page". It first looks the "from" value up in the
maps returned by every IDetailPageOperatorService, then a hard-coded switch,
then falls back to `new X.0zMD()`.

X.0zMD is TikTok's default operator: it fetches the one video named by
`param.getAid()` (DetailModel, /aweme/v1/aweme/detail/). Returning it is
how to pin any video page to the single video that was opened.

## "from" values and who registers them

| Registered by | from values |
|---|---|
| detail.operators.DetailPageOperatorServiceImpl | from_time_line, from_music_children_mode, from_challenge_children_mode, from_window_following, **from_chat**, **from_auto_message**, from_no_request, from_commerce_banner, from_notification_page_repost_entrance |
| im...FavoriteVideoChatDetailPageOperatorService | from_chat_favorite_video_panel |
| tako...TakoDetailPageOperatorServiceImpl (AI bot) | from_chat_inner_cell |
| profile...ProfileDetailPageOperatorServiceImpl | from_school_post, from_profile_self, from_profile_other |
| search...SearchDetailPageOperatorServiceImpl | from_search, from_search_mix, from_search_continuous_loading_card, from_search_continuous_play_card |
| feed.operator.FollowFeedDetailPageOperatorServiceImpl | from_following_sky_light, from_follow_page |
| feed.RecommendFeedDetailPageOperatorServiceImpl | friend_more |
| challenge / music / template / ecommerce / tts / voice / AIME / effect services | from_challenge, from_discovery_challenge, from_search_recalled_challenge, from_music, from_template_detail_activity, from_ttmall_homepage, from_order_center, from_ecom_search, goods_search, from_top_products_card, from_ecom_search_inflow_v2, mix_list_from_ttmall_homepage, from_tts_page, from_voice_conversion_page, from_aime_activity, from_sticker_master_profile, from_card_enhanced_video, from_creative_tool_* |
| hard-coded in X.0zO4.LIZ | from_inbox_detail (X.0zMF), from_notification_page (X.0z6s, a subclass of 0zMD), from_inner_push, from_sticker, from_search_playlist, from_visual_search_result, from_search_ad_no_request, from_detail_activity, from_feeds_operate, from_ad_new_fake_user, from_music_detail, cloud_game_list, mp_page, ... |

## DM videos (from_chat)

The from_chat factory (X.1131 case 3, log tag "DMDetailTrace", string
"operator pick routedAid=") picks one of two operators:

- **DetailChatOperator** (X.0zME): fetches the single opened video.
- **ExtDetailChatOperator2** (X.0zPf): used when a server-side experiment
  (X.0zNu, an int > 0) is on and a "memory station" (X.0IDI.LIZLLL) was
  seeded with the opened video. The station is an IM message browser
  (X.0qCi "chat", X.0qCj "grid", "Unable to create MessageBrowserModel")
  that pages older and newer messages of that conversation and keeps the
  shared videos and stories.

So swiping up from a DM video goes through **the other videos shared in the
same chat**, not recommendations. Confirmed on the phone (swipes on) and in
code (the source is the conversation's message list).

## Hook plan ("Pin video pages")

Hook the return of X.0zO4.LIZ. If `param.getFrom()` is not on an allow list,
return a new X.0zMD instead of the original operator, and make its
cannotLoadMore / cannotLoadLatest answer true. The allow list (a setting)
decides whether profile grids (from_profile_self / from_profile_other) and
chat swiping (from_chat with the station) stay swipeable.
