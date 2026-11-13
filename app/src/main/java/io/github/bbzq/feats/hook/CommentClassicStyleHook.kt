package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Restores the host's built-in comment layout before its experiment lazies are evaluated. */
class CommentClassicStyleHook(env: RoamingEnv) : BaseRoamingHook(env) {
    @Volatile private var nativeReady = false

    override fun startHook() {
        if (isInstalled || env.processName != env.packageName ||
            !ModuleSettings.isCommentClassicStyleEnabled(prefs)) return

        // Leave the host untouched if it no longer ships the native comment container.
        if (loadClass("com.bilibili.app.comment3.CommentV3Fragment") == null) {
            log("CommentClassicStyle: native comment container unavailable")
            return
        }

        val native = readers("com.bilibili.lib.dd.DeviceDecision", kotlin = false)
        val nativeCount = native.count { installReader(it, keyIndex = 0) }
        nativeReady = native.isNotEmpty() && nativeCount == native.size
        if (!nativeReady) {
            log("CommentClassicStyle: native readers incomplete ($nativeCount/${native.size}); keeping current container")
            return
        }

        val kotlin = readers("kntr.base.dd.IDeviceDecisionKt", kotlin = true)
        val kotlinCount = kotlin.count { installReader(it, keyIndex = 1) }
        isInstalled = true
        log("CommentClassicStyle: installed native=$nativeCount, kotlin=$kotlinCount/${kotlin.size}; cold restart required after changing the setting")
    }

    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun readers(owner: String, kotlin: Boolean): List<Method> = runCatching {
        loadClass(owner)?.declaredMethods.orEmpty().filter { method ->
            if (method.returnType != Boolean::class.javaPrimitiveType ||
                Modifier.isAbstract(method.modifiers) || method.isBridge) return@filter false
            val types = method.parameterTypes
            if (kotlin) {
                Modifier.isStatic(method.modifiers) &&
                    method.name in listOf("getBool", "getBool\$default") &&
                    types.size in listOf(4, 6) && types[0].isInterface &&
                    types[1] == String::class.java && types[2] == Boolean::class.javaPrimitiveType &&
                    !types[3].isPrimitive && (types.size == 4 ||
                    types[4] == Int::class.javaPrimitiveType && types[5] == Any::class.java)
            } else {
                !Modifier.isStatic(method.modifiers) && method.name == "getBoolean" &&
                    types.size in 2..8 && types[0] == String::class.java &&
                    types[1] == Boolean::class.javaPrimitiveType
            }
        }
    }.getOrElse {
        log("CommentClassicStyle: could not resolve $owner", it)
        emptyList()
    }

    private fun installReader(method: Method, keyIndex: Int): Boolean = runCatching {
        method.isAccessible = true
        // These readers are global hot paths. Inspect only the key; forward every other call unchanged.
        xposed.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (nativeReady && isClassicCommentExperiment(chain.getArgs()[keyIndex])) {
                    false
                } else {
                    chain.proceed()
                }
            }
        true
    }.getOrElse {
        log("CommentClassicStyle: failed to hook $method", it)
        false
    }
}

internal fun isClassicCommentExperiment(key: Any?): Boolean = when (key) {
    "comment.next_appearance", "comment.next_appearance_experiment_3",
    "comment.kntr.enabled", "comment.kntr.route.enabled",
    "comment.kntr.landscape.enabled", "comment.kntr.story.enabled" -> true
    else -> false
}
