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
    private var under18 = true
    private val active: Boolean
        get() = !disposed && !activity.isFinishing && !activity.isDestroyed

    init {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() { Log.i(TAG, "banner_load_success") }
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e(TAG, "banner_load_failure code=${error.code} domain=${error.domain} message=${error.message}")
            }
        }
    }

    val optionsRequired: Boolean
        get() = preferences.contains("under_18") && !preferences.getBoolean("under_18", true) &&
            consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun start() {
        if (!active || flowStarted) return
        flowStarted = true
        if (!preferences.contains("under_18")) {
            AlertDialog.Builder(activity)
                .setTitle("Advertising privacy")
                .setMessage("Select your age group so we can apply the appropriate advertising privacy settings. We save only this choice on your device, not your date of birth. Your choice does not affect dice results.")
                // Buttons keep both choices visible alongside the explanatory message.
                .setNegativeButton("Under 18") { _, _ -> selectAge(true) }
                .setPositiveButton("18 or older") { _, _ -> selectAge(false) }
                .setCancelable(false)
                .show()
        } else {
            gatherConsent()
        }
    }

    private fun selectAge(isUnder18: Boolean) {
        preferences.edit().putBoolean("under_18", isUnder18).apply()
        gatherConsent()
    }

    private fun gatherConsent() {
        if (!active) return
        under18 = preferences.getBoolean("under_18", true)
        Log.i(TAG, "age_group=${if (under18) "under_18" else "18_or_older"}")
        Log.i(TAG, "mobile_ads_initialization_callback_pending=true")
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTagForUnderAgeOfConsent(if (under18)
                    RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                else RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                .build()
        )
        if (under18) {
            // SDK 23.6 supports TFUA: conservative restricted treatment, no AAID or
            // personalized ads. This is not a declaration that the app targets children.
            // Do not wait on an adult UMP consent status for this restricted path.
            privacyComplete = true
            logConsent("restricted_path_no_consent_form")
            startAdsIfAllowed()
            return
        }
        val parameters = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()
        consent.requestConsentInfoUpdate(activity, parameters, {
            if (active) {
                logConsent("update_success")
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (!active) return@loadAndShowConsentFormIfRequired
                    if (error != null) Log.w(TAG, "consent_form_error code=${error.errorCode} message=${error.message}")
                    privacyComplete = true
                    logConsent("form_complete")
                    startAdsIfAllowed()
                }
            }
        }, { error ->
            if (!active) return@requestConsentInfoUpdate
            Log.w(TAG, "consent_update_error code=${error.errorCode} message=${error.message}")
            // UMP may still permit ads using a valid choice from a previous session.
            privacyComplete = true
            logConsent("update_error")
            startAdsIfAllowed()
        })
    }

    private fun startAdsIfAllowed() {
        if (!active || adsStarted || !preferences.contains("under_18")) return
        if (!privacyComplete || (!under18 && !consent.canRequestAds())) {
            Log.i(TAG, "banner_waiting_for_privacy")
            return
        }
        adsStarted = true
        Log.i(TAG, "mobile_ads_initialization_started")
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                Log.i(TAG, "mobile_ads_initialized=true")
                if (active && privacyComplete && (under18 || consent.canRequestAds())) {
                    val request = AdRequest.Builder()
                    if (under18) {
                        // Explicitly request NPA plus restricted data processing for US states.
                        val extras = Bundle().apply {
                            putString("npa", "1")
                            putInt("rdp", 1)
                        }
                        request.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                    }
                    Log.i(TAG, "banner_load_requested restricted=$under18")
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

    fun changeAgeGroup() {
        Log.i(TAG, "age_group_change_requested")
        disposed = true
        adView.destroy()
        preferences.edit().remove("under_18").apply()
        activity.recreate()
    }

    fun destroy() { disposed = true; adView.destroy() }

    private fun logConsent(stage: String) {
        Log.i(TAG, "consent stage=$stage status=${consent.consentStatus} canRequestAds=${consent.canRequestAds()} restricted=$under18")
    }

    companion object { private const val TAG = "RealDiceAds" }
}
