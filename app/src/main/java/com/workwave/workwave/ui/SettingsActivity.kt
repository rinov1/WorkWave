package com.workwave.workwave.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.google.android.material.snackbar.Snackbar
import com.workwave.workwave.R
import com.workwave.workwave.databinding.ActivitySettingsBinding
import com.workwave.workwave.util.LocaleUtils
import com.workwave.workwave.util.ThemeUtils

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val prefs by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.switchDark.isChecked = ThemeUtils.isDark(this)
        binding.switchDark.setOnCheckedChangeListener { _, checked ->
            ThemeUtils.setDarkTheme(this, checked)
        }

        val time24hDefault = true
        val is24h = prefs.getBoolean("time_24h", time24hDefault)
        binding.switch24h.isChecked = is24h
        binding.switch24h.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("time_24h", checked).apply()
            Snackbar.make(binding.root, getString(R.string.time_format_changed), Snackbar.LENGTH_SHORT).show()
        }

        val datePatterns = listOf(
            "EEEE, d MMMM",
            "dd.MM.yyyy",
            "dd/MM/yyyy"
        )
        val dateAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            datePatterns
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spDateFormat.adapter = dateAdapter

        val savedPattern = prefs.getString("date_format", datePatterns[0]) ?: datePatterns[0]
        val selIndex = datePatterns.indexOf(savedPattern).takeIf { it >= 0 } ?: 0
        binding.spDateFormat.setSelection(selIndex)
        binding.spDateFormat.setOnItemSelectedListenerCompat { position ->
            val pattern = datePatterns[position]
            prefs.edit().putString("date_format", pattern).apply()
        }

        val savedLang = LocaleUtils.getLanguage(this)
        when (savedLang) {
            "ru" -> binding.rbLangRu.isChecked = true
            "en" -> binding.rbLangEn.isChecked = true
            "ko" -> binding.rbLangKo.isChecked = true
            else -> binding.rbLangRu.isChecked = true
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val lang = when (checkedId) {
                binding.rbLangEn.id -> "en"
                binding.rbLangKo.id -> "ko"
                else -> "ru"
            }
            LocaleUtils.setLanguage(this, lang)
            recreate()
        }
    }
}

private fun Spinner.setOnItemSelectedListenerCompat(onSelected: (position: Int) -> Unit) {
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        private var initialized = false
        override fun onItemSelected(
            parent: AdapterView<*>,
            view: View?,
            position: Int,
            id: Long
        ) {
            if (!initialized) {
                initialized = true
                return
            }
            onSelected(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>) {}
    }
}