package io.github.vad4nus.actioncable.check.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.vad4nus.actioncable.check.Harness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private lateinit var harness: Harness

private fun startHarness(context: Context): Harness = Harness(BuildConfig.CABLE_URL).also { harness ->
    if (Build.VERSION.SDK_INT >= 24) {
        harness.setOnline(false)
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = harness.setOnline(true)

                override fun onLost(network: Network) = harness.setOnline(false)
            },
        )
    }
    harness.start()
    ProcessLifecycleOwner.get().lifecycle.addObserver(
        LifecycleEventObserver { owner, _ ->
            harness.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        },
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        super.onCreate(savedInstanceState)
        if (!::harness.isInitialized) harness = startHarness(applicationContext)
        val status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 144, 48, 48)
            setOnClickListener { lifecycleScope.launch { harness.echo("tap") } }
        }
        setContentView(status)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    status.text = harness.describe()
                    delay(500)
                }
            }
        }
    }
}
