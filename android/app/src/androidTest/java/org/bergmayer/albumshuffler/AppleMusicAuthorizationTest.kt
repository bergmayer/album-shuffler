package org.bergmayer.albumshuffler

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apple.android.sdk.authentication.TokenError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Apple's real result parser without signing in or storing fixture tokens. */
@RunWith(AndroidJUnit4::class)
class AppleMusicAuthorizationTest {
    private val platform get() = ApplicationProvider.getApplicationContext<ShufflerApplication>().platform

    @Test fun successfulAuthorizationReturnsToken() {
        assertEquals("test-user-token", platform.authorizationResult(Activity.RESULT_OK,
            Intent().putExtra("music_user_token", "test-user-token")))
    }

    @Test fun canceledResultCannotSaveAToken() {
        assertThrows(AuthenticationFailure::class.java) {
            platform.authorizationResult(Activity.RESULT_CANCELED, Intent().putExtra("music_user_token", "test-user-token"))
        }
    }

    @Test fun missingAndEmptyResultsOfferRecovery() {
        for (data in listOf(null, Intent(), Intent().putExtra("music_user_token", ""))) {
            val error = assertThrows(AuthenticationFailure::class.java) {
                platform.authorizationResult(Activity.RESULT_OK, data)
            }
            assertTrue(error.message!!.contains("Sign-in stuck?"))
        }
    }

    @Test fun backingOutOfStalledAppleMusicOffersRecovery() {
        val error = assertThrows(AuthenticationFailure::class.java) {
            platform.authorizationResult(Activity.RESULT_CANCELED,
                Intent().putExtra("music_user_token_error", TokenError.USER_CANCELLED.errorCode))
        }
        assertTrue(error.message!!.contains("Sign-in stuck?"))
        assertTrue(error.message!!.contains("did not finish connecting"))
    }

    @Test fun specificErrorsSurviveCanceledActivityResult() {
        for ((sdkError, expected) in listOf(
            TokenError.NO_SUBSCRIPTION to "no active Apple Music subscription",
            TokenError.SUBSCRIPTION_EXPIRED to "subscription has expired",
            TokenError.TOKEN_FETCH_ERROR to "could not issue an access token",
        )) {
            val error = assertThrows(AuthenticationFailure::class.java) {
                platform.authorizationResult(Activity.RESULT_CANCELED,
                    Intent().putExtra("music_user_token_error", sdkError.errorCode))
            }
            assertTrue(error.message!!.contains(expected))
        }
    }
}
