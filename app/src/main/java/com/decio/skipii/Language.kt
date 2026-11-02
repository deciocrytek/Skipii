package com.decio.skipii

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The app's language: the phone's (default) or one picked in the account sheet. Android 13+ keeps the pick
 * itself (per-app language, also in Settings › Apps › Skipii › Language) and recreates the window; older
 * phones keep it in prefs "language" and every window is wrapped in it ([wrap]).
 *
 * YouTube is always asked in English (`hl=en`): the parsers, the movie / members-only filters and the
 * bot-check detection read its English words. Skipii shows everything in the app's language itself
 * ([formatCount], [displayAge], [plural]); the sign-in page follows the app's language ([webTag]).
 */
object AppLanguage {
    /** A language Skipii is translated into, named in its own language so it can be found from any other. */
    class Choice(val tag: String, val name: String)

    val choices = listOf(
        Choice("en", "English"),
        Choice("es", "Español"),
        Choice("pt", "Português"),
        Choice("fr", "Français"),
        Choice("de", "Deutsch"),
        Choice("it", "Italiano"),
        Choice("id", "Bahasa Indonesia"),
        Choice("tr", "Türkçe"),
        Choice("vi", "Tiếng Việt"),
        Choice("ru", "Русский"),
        Choice("ar", "العربية"),
        Choice("hi", "हिन्दी"),
        Choice("ja", "日本語"),
    )

    private const val PREFS = "language"
    private const val KEY_TAG = "tag"

    private lateinit var app: Application

    /** The picked language's tag, "" = the phone's. */
    var picked by mutableStateOf("")
        private set

    /** The pick's resources below Android 13 (the Application's own don't follow it there). */
    private var pickedResources: Resources? = null

    fun init(application: Application) {
        app = application
        refresh()
    }

    /** Re-reads the pick: on Android 13+ it can also change in Settings › Apps › Skipii › Language (the window is rebuilt). */
    fun refresh() {
        picked = if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.toLanguageTag().orEmpty()
        } else {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        }
        applyBelow33()
    }

    /** Picks [tag] ("" = the phone's) and rebuilds [activity] in it. */
    fun pick(activity: Activity, tag: String) {
        if (tag == picked) return
        picked = tag
        if (Build.VERSION.SDK_INT >= 33) {
            // Android recreates the window itself once the process has the new configuration.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
            applyBelow33()
            activity.recreate()
        }
    }

    /** The picked tag if it's one of [choices] ("pt-BR" → "pt"), else "" (the phone's). */
    val pickedChoice: String
        get() = choices.firstOrNull { Locale.forLanguageTag(it.tag).language == Locale.forLanguageTag(picked).language }?.tag.orEmpty()

    /** The language the app is shown in right now (the pick, else the phone's). */
    val locale: Locale
        get() = resources.configuration.locales[0] ?: Locale.getDefault()

    /** The phone's own language, whatever was picked. */
    val phoneLocale: Locale get() = Resources.getSystem().configuration.locales[0] ?: Locale.US

    /** The tag for web pages a person reads (Google's sign-in page), never for anything Skipii parses. */
    val webTag: String get() = locale.toLanguageTag()

    /** Resources in the app's language, for code with no composition (services, workers, state objects). */
    val resources: Resources get() = pickedResources ?: app.resources

    /** Below Android 13: a window's base context in the picked language (from attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        if (tag.isEmpty()) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
    }

    private fun applyBelow33() {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = picked
        if (tag.isEmpty()) {
            pickedResources = null
            Locale.setDefault(phoneLocale)
        } else {
            val locales = LocaleList.forLanguageTags(tag)
            pickedResources = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(locales) }).resources
            Locale.setDefault(locales[0])
        }
    }
}

/** A string in the app's language, for code outside a composition (in one, use stringResource). */
fun str(@StringRes id: Int, vararg args: Any): String = AppLanguage.resources.getString(id, *args)

/** A plural string in the app's language; [count] picks the form (any size: see [pluralQuantity]). */
fun plural(@PluralsRes id: Int, count: Long, vararg args: Any): String =
    AppLanguage.resources.getQuantityString(id, pluralQuantity(count), *args)

/**
 * A count as the Int plural rules take, keeping what they look at: the last digits (Russian, Arabic) and
 * whether it's a million or more (French "many"). A plain clamp turned 3 billion into 2,147,483,647 ("few").
 */
fun pluralQuantity(count: Long): Int = when {
    count < 0 -> 0
    count <= Int.MAX_VALUE -> count.toInt()
    else -> (count % 1_000_000_000).toInt() + 1_000_000_000
}
