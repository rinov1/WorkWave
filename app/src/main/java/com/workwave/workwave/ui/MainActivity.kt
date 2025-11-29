package com.workwave.workwave.ui

import android.content.Intent
import android.os.Bundle
import com.workwave.workwave.databinding.ActivityMainBinding
import com.workwave.workwave.util.ThemeUtils

class MainActivity : BaseActivity() { // важно: наследуемся от BaseActivity, чтобы применялась локаль
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applySavedTheme(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.btnOpenRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}