/*
 * Copyright 2026 CleancutTok contributors
 * https://github.com/AS44git/CleancutTok
 *
 * Part of CleancutTok, a fork of Hushfeed (GPL-3.0). Not affiliated with TikTok or ByteDance.
 */
package app.morphe.patches.tiktok.cleancut

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.navigation.feedTabNavigationPatch
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.ApiCallChainFingerprint
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/morphe/extension/tiktok/cleancut/Cleancut;"
private const val UPDATER = "Lapp/morphe/extension/tiktok/cleancut/Updater;"
private const val INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"

/**
 * Lets the updater hand a downloaded release to Android's package installer. TikTok doesn't
 * declare it; without it the installer refuses the session.
 */
private val installPermissionPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { xml ->
            val manifest = xml.documentElement
            val permissions = xml.getElementsByTagName("uses-permission")
            val declared = (0 until permissions.length)
                .any { (permissions.item(it) as Element).getAttribute("android:name") == INSTALL_PERMISSION }
            if (!declared) {
                val element = xml.createElement("uses-permission")
                element.setAttribute("android:name", INSTALL_PERMISSION)
                manifest.insertBefore(element, manifest.firstChild)
            }
        }
    }
}

/**
 * The one method that picks every video page's list source (its "operator"), outside the home
 * feed: DM videos, notifications, search, hashtags, sounds and profiles all come through it.
 * R8 names it LIZ on a class named X.0zO4 in 47.6.3; its strings stay. It switches on the page's
 * `from` value, and when nothing matches it falls back to TikTok's single-video operator.
 * See notes/internals-detail-pages.md.
 */
internal object VideoPageOperatorFactoryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("L", "L", "Lcom/bytedance/jedi/arch/JediViewModel;", "Landroidx/fragment/app/Fragment;"),
    strings = listOf("[UserCard]_DETAIL", "from_inbox_detail", "cloud_game_list"),
)

/**
 * TikTok's default operator among the ones the factory builds: the class with a no-argument
 * constructor whose own `request` reads "notification_page" (it fetches the one video the page
 * names by id). Its subclass for notifications inherits request, so it doesn't match.
 */
private fun ClassDef.isSingleVideoOperator(operatorInterface: String): Boolean =
    operatorInterface in interfaces &&
        methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() } &&
        methods.any { method ->
            method.name == "request" &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.CONST_STRING &&
                        instruction.getReference<StringReference>()?.string == "notification_page"
                } == true
        }

/**
 * TikTok's success handler for a page of Favourites > Videos (getAllFavoritesContent, 30 at a
 * time): an RxJava doOnNext that logs the call. Its strings: the endpoint and "net".
 */
internal object FavoritesPageFingerprint : Fingerprint(
    name = "accept",
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "V",
    strings = listOf("/aweme/v1/aweme/listcollection/", "net"),
)

@Suppress("unused")
val cleancutPatch = bytecodePatch(
    name = "CleancutTok",
    description = "Turns TikTok into its Inbox and your Profile: no For You, Following, Friends, " +
        "Explore, LIVE or Shop feed, no suggested accounts, and a video opened from anywhere but " +
        "a chat or a profile plays on its own. Opens on Inbox. Locked on: nothing in the app " +
        "turns it off.",
) {
    category("CleancutTok")
    dependsOn(settingsPatch, sharedExtensionPatch, feedTabNavigationPatch, blockAuthorPatch, installPermissionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Layer 2: the network gate, as each API call's attempt starts. Throws an IOException
        // for a feed request, which the call reports as a failed request.
        ApiCallChainFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION->gate(Ljava/lang/Object;)V",
        )

        // Layer 3: video pages play one video.
        val factory = VideoPageOperatorFactoryFingerprint.method
        val operatorInterface = factory.returnType
        val single = factory.instructions
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { it.getReference<TypeReference>()?.type }
            .distinct()
            .mapNotNull { classDefByOrNull(it) }
            .singleOrNull { it.isSingleVideoOperator(operatorInterface) }
            ?: throw PatchException(
                "CleancutTok: TikTok's single-video operator is no longer among the ones " +
                    "${factory.definingClass}->${factory.name} builds",
            )
        // The factory's first parameter is the page's parameters (getFrom, getAid); v0 is free
        // at the top of the method, before anything is stored.
        factory.requireLocals("CleancutTok", 1)
        factory.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p0}, $EXTENSION->pinsVideoPage(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :cleancut_keep_list
                new-instance v0, ${single.type}
                invoke-direct {v0}, ${single.type}-><init>()V
                return-object v0
            """,
            ExternalLabel("cleancut_keep_list", factory.getInstruction(0)),
        )

        // Favourites: each page as it arrives (diagnostics for oldest-first, for now).
        FavoritesPageFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION->favoritesPage(Ljava/lang/Object;)V",
        )

        // Updates: a quiet check of this repository's latest release a few seconds after the
        // main screen opens.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $UPDATER->onMainActivity(Landroid/app/Activity;)V",
        )

        // Layers 1 and 4 (the bottom bar, the start tab and the locked settings) live in the
        // extension: Hushfeed's tab filter and start page call into it, and its Settings class
        // locks itself as it loads.
    }
}
