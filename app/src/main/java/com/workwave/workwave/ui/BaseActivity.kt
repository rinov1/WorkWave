package com.workwave.workwave.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.workwave.workwave.util.LocaleUtils

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtils.updateContextLocale(newBase))
    }
}