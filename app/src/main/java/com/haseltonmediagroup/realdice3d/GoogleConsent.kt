package com.haseltonmediagroup.realdice3d

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Only Google's UMP consent flow; no custom age or privacy dialogs. */
class GoogleConsent(private val activity: AppCompatActivity, private val banner: AdView) {
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var started = false
    private var adsStarted = false
    private var disposed = false
    private val active get() = !disposed && !activity.isFinishing && !activity.isDestroyed

    val optionsRequired get() = consent.privacyOptionsRequirementStatus ==
        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun start() {
        if (!active || started) return
        started = true
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            if (active) UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w("RealDiceAds", "UMP: ${error.errorCode}: ${error.message}")
                requestAdsIfPermitted()
            }
        }, { error ->
            Log.w("RealDiceAds", "UMP: ${error.errorCode}: ${error.message}")
            requestAdsIfPermitted()
        })
    }

    private fun requestAdsIfPermitted() {
        if (!active || adsStarted || !consent.canRequestAds()) return
        adsStarted = true
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                if (active && consent.canRequestAds()) banner.loadAd(AdRequest.Builder().build())
            }
        }
    }

    fun showOptions() {
        if (!active || !optionsRequired) return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w("RealDiceAds", "UMP: ${error.errorCode}: ${error.message}")
            if (active) {
                // Discard the previous banner after a privacy change.
                destroy()
                activity.recreate()
            }
        }
    }

    fun destroy() { disposed = true; banner.destroy() }
}

