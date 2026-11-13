package io.github.bbzq.feats.hook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.getStaticObjectField
import io.github.bbzq.feats.hookBefore
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class FreeCopyHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var activeDialog: WeakReference<AlertDialog>? = null
    private val installedTargets = mutableSetOf<String>()

    override fun startHook() {
        if (isInstalled) return
        ensureActivityTracking()
        installTarget("description") {
            val owner = classLoader.findClassOrNull(INTRO_COPY_OWNER) ?: return@installTarget false
            val method = owner.getDeclaredMethod("c", String::class.java, Boolean::class.javaPrimitiveType!!)
            require(method.returnType == Void.TYPE)
            val service = owner.getDeclaredField("b")
            require(service.type.name == INTRO_SERVICE)
            val context = service.type.getDeclaredField("b")
            require(context.type == Context::class.java)
            service.isAccessible = true
            context.isAccessible = true
            env.hookBefore(method) { param ->
                val text = param.args.firstOrNull() as? String ?: return@hookBefore
                val hostContext = context.get(service.get(param.thisObject)) as? Context
                if (takeOverCopy(text, hostContext)) param.result = null
            }
        }
        installTarget("native comment menu") {
            val owner = classLoader.findClassOrNull(NATIVE_COPY_OWNER) ?: return@installTarget false
            val action = classLoader.findClassOrNull(COMMENT_MENU_ACTION) ?: return@installTarget false
            require(action.isEnum && action.enumConstants.any { (it as Enum<*>).name == "COPY" })
            require(owner.getDeclaredField("b").type == Context::class.java)
            require(owner.getDeclaredField("c").type.name == COMMENT_ITEM)
            require(owner.getDeclaredField("i").type == String::class.java)
            val unit = hostUnit() ?: return@installTarget false
            env.hookBefore(owner.getDeclaredMethod("invoke", Any::class.java)) { param ->
                val requested = param.args.firstOrNull() as? Enum<*> ?: return@hookBefore
                if (!action.isInstance(requested) || requested.name != "COPY") return@hookBefore
                val callback = param.thisObject ?: return@hookBefore
                val text = callback.getObjectField("i") as? String ?: return@hookBefore
                if (takeOverCopy(text, callback.getObjectField("b") as? Context)) param.result = unit
            }
        }
        installTarget("legacy comment copy") {
            val owner = classLoader.findClassOrNull(LEGACY_COPY_OWNER) ?: return@installTarget false
            val state = owner.getDeclaredField("b")
            require(state.type.name == LEGACY_COMMENT_STATE)
            state.isAccessible = true
            require(state.type.getDeclaredField("a").type == Context::class.java)
            val content = state.type.getDeclaredField("s")
            require(content.type.name == LEGACY_OBSERVABLE_TEXT)
            content.isAccessible = true
            val readText = content.type.getMethod("getValue")
            // Use the host's own emoji-span conversion on a copy of the displayed rich text.
            val cleaner = classLoader.findClassOrNull("Eh.a")?.getDeclaredMethod("a", SpannableStringBuilder::class.java)
                ?: return@installTarget false
            require(Modifier.isStatic(cleaner.modifiers) && cleaner.returnType == Void.TYPE)
            cleaner.isAccessible = true
            env.hookBefore(owner.getDeclaredMethod("call", Any::class.java)) { param ->
                if (!isRemoveDirectCopyEnabled()) return@hookBefore
                val model = state.get(param.thisObject) ?: return@hookBefore
                val raw = readText.invoke(content.get(model)) as? CharSequence ?: return@hookBefore
                val text = SpannableStringBuilder(raw)
                cleaner.invoke(null, text)
                if (takeOverCopy(text.toString(), model.getObjectField("a") as? Context)) param.result = true
            }
        }
        installTarget("Compose comment menu") {
            val owner = classLoader.findClassOrNull(COMPOSE_COPY_OWNER) ?: return@installTarget false
            require(owner.getDeclaredField("b").type.name == "pf1.j")
            require(owner.getDeclaredField("c").type.name == "kntr.common.trio.toast.Toaster")
            val store = owner.getDeclaredField("e").apply { isAccessible = true }
            require(store.type.name == "kntr.common.comment.page.store.PageStore")
            val pageState = owner.getDeclaredField("f").apply { isAccessible = true }
            require(pageState.type.name == "qf1.g")
            val position = classLoader.findClassOrNull(COMPOSE_COPY_POSITION) ?: return@installTarget false
            val copyPosition = position.enumConstants.single { (it as Enum<*>).name == "COPY" }
            val action = classLoader.findClassOrNull("lf1.a\$s") ?: return@installTarget false
            val constructor = action.declaredConstructors.single { c ->
                c.parameterTypes.map { it.name } == listOf("pf1.j", "kntr.common.comment.common.model.O", COMPOSE_COPY_POSITION)
            }.apply { isAccessible = true }
            val dispatch = store.type.getMethod("c", classLoader.loadClass("Xe1.a"))
            val currentState = pageState.type.getMethod("k")
            val unit = hostUnit() ?: return@installTarget false
            env.hookBefore(owner.getDeclaredMethod("invoke", Any::class.java)) { param ->
                val text = param.args.firstOrNull() as? String ?: return@hookBefore
                if (!takeOverCopy(text, null)) return@hookBefore
                val callback = param.thisObject ?: return@hookBefore
                // Preserve the menu-close/report action preceding the host's clipboard write.
                runCatching {
                    val event = constructor.newInstance(callback.getObjectField("b"), currentState.invoke(pageState.get(callback)), copyPosition)
                    dispatch.invoke(store.get(callback), event)
                }.onFailure { log("FreeCopy: failed to finish Compose copy menu action", it) }
                param.result = unit
            }
        }
        installTarget("private message copy menu") {
            val owner = classLoader.findClassOrNull(PRIVATE_COPY_OWNER) ?: return@installTarget false
            val activity = owner.getDeclaredField("a").apply { isAccessible = true }
            require(activity.type.name == PRIVATE_CONVERSATION && Activity::class.java.isAssignableFrom(activity.type))
            val copyLabel = owner.getDeclaredField("b").apply { isAccessible = true }
            require(copyLabel.type == String::class.java)
            val message = owner.getDeclaredField("c").apply { isAccessible = true }
            require(message.type.name == PRIVATE_MESSAGE)
            val popup = owner.getDeclaredField("h").apply { isAccessible = true }
            require(PopupWindow::class.java.isAssignableFrom(popup.type))
            val method = owner.getDeclaredMethod("a", String::class.java)
            require(method.returnType == Void.TYPE)
            env.hookBefore(method) { param ->
                if (!isRemoveDirectCopyEnabled()) return@hookBefore
                val callback = param.thisObject ?: return@hookBefore
                // Compare the captured copy item, preserving localized delete/report/forward items.
                if (param.args.firstOrNull() != copyLabel.get(callback)) return@hookBefore
                val context = activity.get(callback) as? Context ?: return@hookBefore
                val model = message.get(callback) ?: return@hookBefore
                val text = privateMessageText(model, context) ?: return@hookBefore
                if (!takeOverCopy(text, context)) return@hookBefore
                runCatching { (popup.get(callback) as? PopupWindow)?.dismiss() }
                    .onFailure { log("FreeCopy: failed to dismiss private message copy menu", it) }
                param.result = null
            }
        }
        installTarget("Compose private message copy menu") {
            val owner = classLoader.findClassOrNull(COMPOSE_PRIVATE_COPY_OWNER) ?: return@installTarget false
            val item = classLoader.findClassOrNull("com.bapis.bilibili.app.im.v1.G1") ?: return@installTarget false
            val copyItem = classLoader.findClassOrNull("com.bapis.bilibili.app.im.v1.D1\$e") ?: return@installTarget false
            val method = owner.getDeclaredMethod(
                "a", item, classLoader.loadClass("k81.q"),
                classLoader.loadClass("kntr.app.im.chat.ui.a"),
                classLoader.loadClass("kotlin.coroutines.jvm.internal.SuspendLambda"),
            )
            require(Modifier.isStatic(method.modifiers) && method.returnType == Any::class.java)
            val readContent = item.getMethod("getContent")
            val readItem = readContent.returnType.getMethod("getItem")
            val readCopyValue = copyItem.getMethod("getValue")
            val readCopyText = readCopyValue.returnType.getMethod("getContent")
            require(readCopyText.returnType == String::class.java)
            val unit = hostUnit() ?: return@installTarget false
            env.hookBefore(method) { param ->
                if (!isRemoveDirectCopyEnabled()) return@hookBefore
                val selected = param.args.firstOrNull() ?: return@hookBefore
                val content = readContent.invoke(selected) ?: return@hookBefore
                val action = readItem.invoke(content) ?: return@hookBefore
                // This dispatcher also handles delete, recall, report, stickers and translation.
                if (!copyItem.isInstance(action)) return@hookBefore
                val value = readCopyValue.invoke(action) ?: return@hookBefore
                val text = readCopyText.invoke(value) as? String ?: return@hookBefore
                // Return to the UI caller so its menu cleanup and click reporting still run.
                if (takeOverCopy(text, null)) param.result = unit
            }
        }
        installLegacyDynamicCopy("SS.o", "SS.p", "androidx.room.support.c", 1)
        installLegacyDynamicCopy("SS.q", "SS.r", "com.bilibili.bplus.followinglist.opus.u", 0)
        installLegacyDynamicCopy("SS.u", "SS.y", "SS.g", 0)
        installLegacyDynamicCopy("SS.z", "SS.A", "com.bilibili.bplus.followinglist.opus.u", 0)
        installTarget("native dynamic text long press") {
            val owner = classLoader.findClassOrNull("rT.b") ?: return@installTarget false
            require(owner.getDeclaredField("b").type.name == "rT.c")
            val model = owner.getDeclaredField("c").apply { isAccessible = true }
            require(model.type.name == "xS.Y1")
            val content = model.type.getDeclaredField("l").apply { isAccessible = true }
            require(content.type == String::class.java)
            val method = owner.getDeclaredMethod("onLongClick", View::class.java)
            require(method.returnType == Boolean::class.javaPrimitiveType)
            env.hookBefore(method) { param ->
                val view = param.args.firstOrNull() as? View ?: return@hookBefore
                val text = content.get(model.get(param.thisObject)) as? String ?: return@hookBefore
                if (takeOverCopy(text, view.context)) param.result = true
            }
        }
        installDynamicSummaryCopy("dynamic text long press", "d", "d")
        installDynamicSummaryCopy("dynamic expanded text long press", "e", "e")
        installTarget("dynamic list copy effect") {
            val owner = classLoader.findClassOrNull(DYNAMIC_COPY_COLLECTOR) ?: return@installTarget false
            val effect = classLoader.findClassOrNull(DYNAMIC_COPY_EFFECT) ?: return@installTarget false
            val copyEffect = classLoader.findClassOrNull("$DYNAMIC_COPY_EFFECT\$b") ?: return@installTarget false
            require(effect.isAssignableFrom(copyEffect))
            val content = copyEffect.getDeclaredField("a").apply { isAccessible = true }
            require(content.type == String::class.java)
            val method = owner.getDeclaredMethod("a", effect, classLoader.loadClass("kotlin.coroutines.Continuation"))
            require(method.returnType == Any::class.java)
            val unit = hostUnit() ?: return@installTarget false
            env.hookBefore(method) { param ->
                val request = param.args.firstOrNull() ?: return@hookBefore
                if (!copyEffect.isInstance(request)) return@hookBefore
                val text = content.get(request) as? String ?: return@hookBefore
                if (takeOverCopy(text, null)) param.result = unit
            }
        }
        isInstalled = installedTargets.size == 14
        log("startHook: FreeCopy, adapted=${installedTargets.joinToString()}")
    }

    private fun installLegacyDynamicCopy(ownerName: String, bindingName: String, formatterName: String, formatterKind: Int) {
        installTarget("native dynamic long press $ownerName") {
            val owner = classLoader.findClassOrNull(ownerName) ?: return@installTarget false
            val binding = owner.getDeclaredField("b").apply { isAccessible = true }
            require(binding.type.name == bindingName)
            val formatter = classLoader.findClassOrNull(formatterName) ?: return@installTarget false
            val transform = formatter.getDeclaredMethod("invoke", Any::class.java).apply { isAccessible = true }
            val instance = formatter.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.newInstance(formatterKind)
            val method = owner.getDeclaredMethod("onLongClick", View::class.java)
            require(method.returnType == Boolean::class.javaPrimitiveType)
            env.hookBefore(method) { param ->
                if (!isRemoveDirectCopyEnabled()) return@hookBefore
                val view = param.args.firstOrNull() as? View ?: return@hookBefore
                val holder = binding.get(param.thisObject) ?: return@hookBefore
                if (holder.getObjectField("c") == null) return@hookBefore
                if (ownerName == "SS.o" && holder.getObjectField("e")?.callMethod("n") == null) return@hookBefore
                val model = holder.getObjectField("d") ?: return@hookBefore
                val content = model.getObjectField("k") ?: return@hookBefore
                val parts = if (ownerName == "SS.q" || ownerName == "SS.z") {
                    content.getObjectField("d") as? Iterable<*>
                } else content as? Iterable<*>
                if (parts == null) return@hookBefore
                // Reuse the exact host formatter: omit vote/lottery nodes and keep link text.
                val text = parts.joinToString("") { transform.invoke(instance, it).toString() }
                if (takeOverCopy(text, view.context)) param.result = true
            }
        }
    }

    private fun installDynamicSummaryCopy(name: String, ownerSuffix: String, contentField: String) {
        installTarget(name) {
            val owner = classLoader.findClassOrNull("$DYNAMIC_SUMMARY_PACKAGE.$ownerSuffix") ?: return@installTarget false
            val model = owner.getDeclaredField("b").apply { isAccessible = true }
            require(model.type.name == "$DYNAMIC_SUMMARY_PACKAGE.j")
            val content = model.type.getDeclaredField(contentField).apply { isAccessible = true }
            require(content.type == String::class.java)
            require(owner.getDeclaredField("c").type.name == "kntr.common.trio.toast.Toaster")
            require(owner.getDeclaredField("d").type == String::class.java)
            val method = owner.getDeclaredMethod("invoke")
            require(method.returnType == Any::class.java)
            val unit = hostUnit() ?: return@installTarget false
            env.hookBefore(method) { param ->
                val text = content.get(model.get(param.thisObject)) as? String ?: return@hookBefore
                if (takeOverCopy(text, null)) param.result = unit
            }
        }
    }

    private fun privateMessageText(message: Any, context: Context): String? {
        val content by lazy { message.callMethod("getContent") }
        return when (message.javaClass.name) {
            "$PRIVATE_MESSAGE_PACKAGE.TextMessage" -> content?.getObjectField("a") as? String
            "$PRIVATE_MESSAGE_PACKAGE.NotifyMessage" -> {
                val body = content ?: return null
                val text = StringBuilder()
                fun appendPart(value: String?) {
                    if (!value.isNullOrEmpty()) text.append(value.trim()).append('\n')
                }
                appendPart(body.getObjectField("a") as? String)
                appendPart(body.getObjectField("b") as? String)
                val modules = body.getObjectField("k") as? Iterable<*>
                modules?.filterNotNull()?.forEach { module ->
                    appendPart("${module.getObjectField("title")}：${module.getObjectField("detail")}")
                }
                text.toString().removeSuffix("\n")
            }
            else -> if (classLoader.findClassOrNull("dV.d")?.isInstance(message) == true) {
                message.callMethod("getSimpleText", context) as? String
            } else null
        }
    }

    private fun installTarget(name: String, install: () -> Boolean) {
        if (name in installedTargets) return
        runCatching { install() }
            .onSuccess { if (it) installedTargets += name else log("FreeCopy: unadapted $name; retaining native behavior") }
            .onFailure { log("FreeCopy: could not adapt $name; retaining native behavior", it) }
    }

    private fun hostUnit(): Any? = classLoader.findClassOrNull("kotlin.Unit")?.getStaticObjectField("INSTANCE")

    private fun takeOverCopy(text: String, context: Context?): Boolean {
        if (!isRemoveDirectCopyEnabled() || text.isBlank()) return false
        return !isEnhancedCopyEnabled() || showCopyDialog(text, context)
    }

    private fun ensureActivityTracking() {
        val application = env.hostContext as? Application ?: return
        if (!callbacksRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    topActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (topActivity?.get() === activity) {
                        topActivity = null
                    }
                }
            },
        )
    }

    private fun isRemoveDirectCopyEnabled(): Boolean =
        ModuleSettings.isDisableLongPressCopyEnabled(prefs)

    private fun isEnhancedCopyEnabled(): Boolean =
        isRemoveDirectCopyEnabled() && ModuleSettings.isEnhanceLongPressCopyEnabled(prefs)

    private fun findActivity(context: Context?): Activity? {
        var current = context
        repeat(16) {
            val candidate = current
            if (candidate is Activity) return candidate
            current = (candidate as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun showCopyDialog(text: String, context: Context?): Boolean {
        // All adapted targets are UI callbacks. Preserve native behavior if a new host moves one
        // to a worker thread, rather than suppressing a copy for a dialog we cannot open.
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val targetActivity = findActivity(context) ?: topActivity?.get()
        if (targetActivity == null || targetActivity.isFinishing || targetActivity.isDestroyed) {
            log("FreeCopy skipped dialog because no foreground activity is available")
            return false
        }

        return runCatching {
            if (activeDialog?.get()?.isShowing == true) return@runCatching true
            val moduleContext = env.moduleContext ?: run {
                log("FreeCopy skipped dialog because module context is unavailable")
                return@runCatching false
            }
            val dialogTitle = moduleContext.getString(R.string.free_copy_title)
            val originalCopyText = moduleContext.getString(R.string.free_copy_original_button)

            val themeId = targetActivity.resources.getIdentifier("AppTheme.Dialog.Alert", "style", targetActivity.packageName)
            val builder = if (themeId != 0) {
                AlertDialog.Builder(targetActivity, themeId)
            } else {
                AlertDialog.Builder(targetActivity)
            }

            var message: TextView? = null
            val dialog = builder
                .setTitle(dialogTitle)
                .setMessage(text)
                .setPositiveButton(originalCopyText) { _, _ ->
                    val source = message?.text ?: text
                    val start = message?.selectionStart ?: -1
                    val end = message?.selectionEnd ?: -1
                    val selected = if (start >= 0 && end >= 0 && start != end && maxOf(start, end) <= source.length) {
                        source.subSequence(minOf(start, end), maxOf(start, end))
                    } else source
                    val clipboard = targetActivity.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("text", selected))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            activeDialog = WeakReference(dialog)
            dialog.setOnDismissListener { if (activeDialog?.get() === dialog) activeDialog = null }
            message = dialog.findViewById<TextView>(android.R.id.message)
            message?.setTextIsSelectable(true)
            true
        }.onFailure { throwable ->
            log("FreeCopy dialog error", throwable)
        }.getOrDefault(false)
    }

    private companion object {
        // Signatures and captured fields verified against 9.16.0 (9160300), APK SHA-256
        // 27acca0255891730b7a7f6ff69aabf4daa4106d893903343d62826c21a6b33f8.
        private const val INTRO_SERVICE = "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService"
        private const val INTRO_COPY_OWNER = "$INTRO_SERVICE\$b"
        private const val NATIVE_COPY_OWNER = "com.bilibili.app.comment3.ui.holder.Q"
        private const val COMMENT_MENU_ACTION = "com.bilibili.app.comment3.data.model.CommentItem\$MenuItem\$Action"
        private const val COMMENT_ITEM = "com.bilibili.app.comment3.data.model.CommentItem"
        private const val LEGACY_COMMENT_STATE = "Fh.f0"
        private const val LEGACY_COPY_OWNER = "$LEGACY_COMMENT_STATE\$t"
        private const val LEGACY_OBSERVABLE_TEXT = "com.bilibili.app.comm.comment2.basemvvm.observable.LazyObservableField"
        private const val COMPOSE_COPY_OWNER = "kntr.common.comment.page.ui.D0"
        private const val COMPOSE_COPY_POSITION = "kntr.common.comment.card.action.CardReportAction\$CommentCardAllClick\$Pos"
        private const val PRIVATE_COPY_OWNER = "com.bilibili.bplus.im.conversation.r"
        private const val COMPOSE_PRIVATE_COPY_OWNER = "kntr.app.im.chat.ui.utils.f"
        private const val PRIVATE_CONVERSATION = "com.bilibili.bplus.im.conversation.ConversationActivity"
        private const val PRIVATE_MESSAGE = "com.bilibili.bplus.im.business.model.BaseTypedMessage"
        private const val PRIVATE_MESSAGE_PACKAGE = "com.bilibili.bplus.im.business.message"
        private const val DYNAMIC_SUMMARY_PACKAGE = "kntr.app.following.card.opusSummary"
        private const val DYNAMIC_COPY_EFFECT = "kntr.app.following.followinglist.statemachine.g"
        private const val DYNAMIC_COPY_COLLECTOR = "kntr.app.following.followinglist.FollowingListScreenKt\$FollowingListContent\$8\$1\$1"
        private val callbacksRegistered = AtomicBoolean(false)

        @Volatile
        private var topActivity: WeakReference<Activity>? = null
    }
}

