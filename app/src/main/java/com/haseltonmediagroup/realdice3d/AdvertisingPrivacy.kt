package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.ads.mediation.admob.AdMobAdapter

/** Advertising privacy is separate from the renderer and cannot change roll results. */
class AdvertisingPrivacy(private val activity: AppCompatActivity, private val adView: AdView) {
    private val preferences = activity.getSharedPreferences("realdice_privacy", Context.MODE_PRIVATE)
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var adsStarted = false
    private var disposed = false
    private var flowStarted = false
    private var privacyComplete = false
    private var diagnostic = "Advertising privacy check has not started."
    private val active: Boolean
        get() = !disposed && !activity.isFinishing && !activity.isDestroyed

    init {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() { record("Banner loaded successfully.") }
            override fun onAdFailedToLoad(error: LoadAdError) {
                record("Banner failed: code=${error.code}\ndomain=${error.domain}\n${error.message}")
                Log.e(TAG, "banner_load_failure code=${error.code} domain=${error.domain} message=${error.message}")
            }
        }
    }

    val optionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun start() {
        if (!active || flowStarted) return
        flowStarted = true
        // Remove the old local age category; the app no longer asks for or uses it.
        preferences.edit().remove("under_18").apply()
        gatherConsent()
    }

    private fun gatherConsent() {
        if (!active) return
        record("Checking Google advertising privacy requirements. No age selection is requested.")
        Log.i(TAG, "mobile_ads_initialization_callback_pending=true")
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTagForUnderAgeOfConsent(RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_UNSPECIFIED)
                .build()
        )
        val parameters = ConsentRequestParameters.Builder()
            .build()
        consent.requestConsentInfoUpdate(activity, parameters, {
            if (active) {
                logConsent("update_success")
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (!active) return@loadAndShowConsentFormIfRequired
                    if (error != null) {
                        record("Google privacy form failed: code=${error.errorCode}\n${error.message}")
                    } else {
                        record("Google privacy flow completed. Ad requests permitted: ${consent.canRequestAds()}")
                    }
                    privacyComplete = true
                    logConsent("form_complete")
                    startAdsIfAllowed()
                }
            }
        }, { error ->
            if (!active) return@requestConsentInfoUpdate
            Log.w(TAG, "consent_update_error code=${error.errorCode} message=${error.message}")
            record("Google privacy update failed: code=${error.errorCode}\n${error.message}")
            // UMP may still permit ads using a valid choice from a previous session.
            privacyComplete = true
            logConsent("update_error")
            startAdsIfAllowed()
        })
    }

    private fun startAdsIfAllowed() {
        if (!active || adsStarted) return
        if (!privacyComplete || !consent.canRequestAds()) {
            Log.i(TAG, "banner_waiting_for_privacy")
            return
        }
        adsStarted = true
        record("Google privacy permits ads. Initializing Mobile Ads.")
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                Log.i(TAG, "mobile_ads_initialized=true")
                if (active && privacyComplete && consent.canRequestAds()) {
                    val request = AdRequest.Builder()
                    // No age profiling: request non-personalized, restricted ads for everyone.
                    val extras = Bundle().apply {
                        putString("npa", "1")
                        putInt("rdp", 1)
                    }
                    request.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                    record("Mobile Ads initialized. Banner request sent (non-personalized / restricted).")
                    adView.loadAd(request.build())
                }
            }
        }
    }

    fun showOptions() {
        if (!optionsRequired) return
        adView.pause()
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (!disposed && !activity.isFinishing && !activity.isDestroyed) {
                if (error != null) {
                    Log.w(TAG, "privacy_options_error code=${error.errorCode} message=${error.message}")
                    adView.resume()
                    AlertDialog.Builder(activity).setTitle("Privacy choices unavailable")
                        .setMessage("Please check your connection and try again.")
                        .setPositiveButton("OK", null).show()
                } else {
                    // Recreate the banner so it uses the updated privacy choices.
                    activity.recreate()
                }
            }
        }
    }

    fun showDiagnostics() {
        AlertDialog.Builder(activity)
            .setTitle("Advertising status — 1.0.2 (3)")
            .setMessage("$diagnostic\n\nGoogle consent status: ${consent.consentStatus}\nGoogle permits ad requests: ${consent.canRequestAds()}\nBanner area: ${adView.width} × ${adView.height}px\n\nAdMob approval and available inventory also affect ad delivery.")
            .setPositiveButton("CLOSE", null)
            .setNeutralButton("RETRY") { _, _ -> activity.recreate() }
            .show()
    }

    fun destroy() { disposed = true; adView.destroy() }

    private fun logConsent(stage: String) {
        Log.i(TAG, "consent stage=$stage status=${consent.consentStatus} canRequestAds=${consent.canRequestAds()}")
    }

    private fun record(message: String) { diagnostic = message; Log.i(TAG, message) }

    companion object { private const val TAG = "RealDiceAds" }
}
