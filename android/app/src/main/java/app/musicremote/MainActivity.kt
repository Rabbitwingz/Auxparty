package app.musicremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.musicremote.ui.AuxpartyApp
import app.musicremote.ui.HostViewModel

class MainActivity : ComponentActivity() {

    private val vm: HostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuxpartyApp(vm) }
    }
}
