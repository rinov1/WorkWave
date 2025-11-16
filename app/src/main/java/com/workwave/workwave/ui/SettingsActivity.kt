package com.workwave.workwave.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.workwave.workwave.databinding.ActivitySettingsBinding
import com.workwave.workwave.util.ThemeUtils

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.switchDark.isChecked = ThemeUtils.isDark(this)
        binding.switchDark.setOnCheckedChangeListener { _, checked ->
            ThemeUtils.setDarkTheme(this, checked)
        }
    }
}