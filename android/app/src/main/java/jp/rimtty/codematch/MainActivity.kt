package jp.rimtty.codematch

import android.app.LocaleManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import jp.rimtty.codematch.core.designsystem.CodeMatchTheme
import jp.rimtty.codematch.locale.AppLanguageSynchronizer
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var appLanguageSynchronizer: AppLanguageSynchronizer

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Scanner state is refreshed by its lifecycle observer. Permission
        // values are intentionally not logged or retained here.
    }

    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = newBase.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) {
                val localizedConfiguration = Configuration(newBase.resources.configuration).apply {
                    setLocales(locales)
                }
                super.attachBaseContext(
                    newBase.createConfigurationContext(localizedConfiguration),
                )
                return
            }
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestDeclaredNearbyPermissions()
        enableEdgeToEdge()
        setContent {
            CodeMatchTheme {
                CodeMatchApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Android 13 can recreate this Activity while the Application process
        // remains alive when the user changes the app language in system
        // settings. Reconcile here so the repository-backed UI state follows
        // that change as well as the framework resources.
        lifecycleScope.launch {
            appLanguageSynchronizer.synchronizeOnStartup()
        }
    }

    private fun requestDeclaredNearbyPermissions() {
        val declared = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val missing = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { permission ->
            permission in declared && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) nearbyPermissionLauncher.launch(missing.toTypedArray())
    }
}
