package com.haseltonmediagroup.realdice3d

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Google's regional consent forms only; no custom age or consent UI. */
class GoogleConsent(private val activity: AppCompatActivity, private val banner: AdView) {
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var formsAllowed = false
    private var updateComplete = false
    private var updateSucceeded = false
    private var formStarted = false
    private var adsStarted = false
    private var disposed = false
    private val active get() = !disposed && !activity.isFinishing && !activity.isDestroyed

    val optionsRequired get() = consent.privacyOptionsRequirementStatus ==
        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun update() {
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            updateComplete = true
            updateSucceeded = true
            continueConsent()
        }, { error ->
            Log.w("RealDiceAds", "UMP: ${error.errorCode}: ${error.message}")
            updateComplete = true
            continueConsent()
        })
    }

    // The existing Terms dialog keeps its original behavior and text.
    fun allowForm() {
        formsAllowed = true
        continueConsent()
    }

    private fun continueConsent() {
        if (!active || !formsAllowed || !updateComplete || formStarted) return
        formStarted = true
        if (updateSucceeded) {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w("RealDiceAds", "UMP: ${error.errorCode}: ${error.message}")
                requestAdsIfPermitted()
            }
        } else {
            // UMP can retain valid consent from an earlier launch after an update error.
            requestAdsIfPermitted()
        }
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
            requestAdsIfPermitted()
        }
    }

    fun destroy() {
        disposed = true
        banner.destroy()
    }
}
