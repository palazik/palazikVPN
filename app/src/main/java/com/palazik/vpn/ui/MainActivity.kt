package com.palazik.vpn.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.palazik.vpn.ui.screen.AppNavHost
import com.palazik.vpn.ui.theme.palazikVPNTheme
import com.palazik.vpn.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val ui by vm.ui.collectAsState()
            palazikVPNTheme(
                appTheme          = ui.appTheme,
                darkModePreference= ui.darkMode,
            ) {
                AppNavHost(vm = vm, permLauncher = vpnPermLauncher)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "palazikvpn") {
            val raw = data.toString()
            vm.importProfileFromLink(raw)
        }
    }
}
