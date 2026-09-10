package com.haseltonmediagroup.realdice3d

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Advertising privacy is separate from the renderer and cannot change roll results. */
class AdvertisingPrivacy(private val activity: AppCompatActivity, private val adView: AdView) {
    private val preferences = activity.getSharedPreferences("realdice_privacy", Context.MODE_PRIVATE)
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var adsStarted = false
    private var disposed = false

    val optionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun start() {
        if (!preferences.contains("under_18")) {
            AlertDialog.Builder(activity)
                .setTitle("Advertising privacy")
                .setMessage("Select your age group so we can apply the appropriate advertising privacy settings. We save only this choice on your device, not your date of birth. Your choice does not affect dice results.")
                .setItems(arrayOf("Under 18", "18 or older")) { _, selected ->
                    preferences.edit().putBoolean("under_18", selected == 0).apply()
                    gatherConsent()
                }
                .setNegativeButton("NOT NOW", null)
                .show()
        } else {
            gatherConsent()
        }
    }

    private fun gatherConsent() {
        if (disposed || activity.isFinishing || activity.isDestroyed) return
        val underAge = preferences.getBoolean("under_18", true)
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .setTagForUnderAgeOfConsent(if (underAge)
                    RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                else RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE)
                .build()
        )
        val parameters = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(underAge)
            .build()
        consent.requestConsentInfoUpdate(activity, parameters, {
            if (!disposed && !activity.isFinishing && !activity.isDestroyed) {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    startAdsIfAllowed()
                }
            }
        }, {
            // Leave dice usable without advertising when the privacy check fails.
        })
    }

    private fun startAdsIfAllowed() {
        if (disposed || adsStarted || activity.isFinishing || activity.isDestroyed ||
            !preferences.contains("under_18") || !consent.canRequestAds()) return
        adsStarted = true
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                if (!disposed && !activity.isFinishing && !activity.isDestroyed && consent.canRequestAds()) {
                    adView.loadAd(AdRequest.Builder().build())
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
        disposed = true
        adView.destroy()
        preferences.edit().remove("under_18").apply()
        activity.recreate()
    }

    fun destroy() { disposed = true; adView.destroy() }
}
