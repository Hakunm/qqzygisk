package com.qm.qqzygisk.ui.activity

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.parasitic.activity.proxy.ModuleActivity

abstract class BaseActivity : ComponentActivity(), ModuleActivity {

    override val moduleTheme get() = R.style.Theme_AppDefault

    override fun getClassLoader() = delegate.getClassLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        super.onCreate(savedInstanceState)
        onCreate()
    }

    abstract fun onCreate()

    override fun onConfigurationChanged(newConfig: Configuration) {
        delegate.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        delegate.onRestoreInstanceState(savedInstanceState)
        super.onRestoreInstanceState(savedInstanceState)
    }
}
