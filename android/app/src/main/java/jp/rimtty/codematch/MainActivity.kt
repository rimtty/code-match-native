package jp.rimtty.codematch

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import jp.rimtty.codematch.core.designsystem.CodeMatchTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
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
        enableEdgeToEdge()
        setContent {
            CodeMatchTheme {
                CodeMatchApp()
            }
        }
    }
}
