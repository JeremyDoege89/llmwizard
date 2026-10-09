package dev.dreamz.llmwizard

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

@HiltAndroidApp
class LlmWizardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
    }

    companion object {
        /**
         * Android registers a cut-down BouncyCastle under the provider name "BC", which lacks
         * algorithms sshj negotiates (PLAN.md 5.1). Replace it with the full provider before any
         * SSH connection is made.
         */
        fun installBouncyCastle() {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
