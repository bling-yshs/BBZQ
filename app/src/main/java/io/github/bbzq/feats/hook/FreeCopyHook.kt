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
import android.widget.TextView
import io.github.bbzq.ModuleSettings
import io.github.bbzq.R
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
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
        isInstalled = installedTargets.size == 4
        log("startHook: FreeCopy, adapted=${installedTargets.joinToString()}")
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
        private val callbacksRegistered = AtomicBoolean(false)

        @Volatile
        private var topActivity: WeakReference<Activity>? = null
    }
}

