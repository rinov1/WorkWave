package com.workwave.workwave.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

object LocaleUtils {

    private const val PREFS_NAME = "settings"
    private const val KEY_LANGUAGE = "language"
    private const val DEFAULT_LANGUAGE = "ru"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, lang)
            .apply()
    }

    fun updateContextLocale(context: Context): ContextWrapper {
        val lang = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config: Configuration = context.resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        val newContext = context.createConfigurationContext(config)
        return ContextWrapper(newContext)
    }
}