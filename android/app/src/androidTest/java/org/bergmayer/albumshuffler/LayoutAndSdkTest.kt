package org.bergmayer.albumshuffler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Rendering fixtures live only in the test APK; the app always uses real MusicKit. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LayoutAndSdkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sample = Track("test", "test", "A song with a long title for layout testing", "An artist", "An album")

    private fun show(width: Int, height: Int, dark: Boolean = false, fontScale: Float = 1f, connected: Boolean = true) {
        val app = compose.activity.application as ShufflerApplication
        val model = ShufflerViewModel(app)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        ShufflerScreen(ShufflerState(connected = connected), PlayerState(sample, playing = true, canNext = true, canPrevious = true), model, WindowInsets(0, 0, 0, 0)) {}
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val file = File(compose.activity.getExternalFilesDir(null), "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun phoneLandscapeKeepsPlayAndTransportVisible() {
        show(840, 400)
        compose.onNodeWithText("Open random album").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("Playlist Subset").assertIsDisplayed()
        compose.onNodeWithText("Complete Album").assertIsDisplayed()
        val controls = compose.onNodeWithTag("shuffle-controls").fetchSemanticsNode().boundsInRoot
        val player = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape should have two separate columns", controls.right <= player.left)
        val transport = compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot
        assertTrue("The whole transport button must fit", transport.bottom <= player.bottom)
        screenshot("phone-landscape")
    }

    @Test fun tabletLandscapeUsesBothColumns() {
        show(1100, 760, dark = true)
        compose.onNodeWithText("Playlist Subset").assertIsDisplayed()
        compose.onNodeWithText("Complete Album").assertIsDisplayed()
        compose.onNodeWithText("Open random album").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next track").assertIsDisplayed()
        screenshot("tablet-landscape-dark")
    }

    @Test fun portraitHasFooterAndVisibleActions() {
        show(400, 840)
        compose.onNodeWithText("Open random album").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        val controls = compose.onNodeWithTag("shuffle-controls").fetchSemanticsNode().boundsInRoot
        val player = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
        assertTrue("Portrait should stack controls above playback", controls.bottom <= player.top)
        screenshot("phone-portrait")
    }

    @Test fun enlargedTextKeepsPrimaryActionsAccessible() {
        show(840, 480, fontScale = 1.5f)
        compose.onNodeWithText("Open random album").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed()
        screenshot("landscape-large-text")
    }

    @Test fun externalAlbumShowsHandoffWithoutTransportControls() {
        val app = compose.activity.application as ShufflerApplication
        val model = ShufflerViewModel(app)
        val album = Album("123", "Selected album", "Artist", listOf(sample), "https://music.apple.com/us/album/title/123")
        compose.setContent {
            MaterialTheme {
                ShufflerScreen(ShufflerState(connected = true), PlayerState(track = sample, externalAlbum = album), model, WindowInsets(0, 0, 0, 0)) {}
            }
        }
        compose.onNodeWithText("Selected album").assertIsDisplayed()
        compose.onNodeWithText("Tap Play in Apple Music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open in Apple Music").assertIsDisplayed()
    }

    @Test fun nativeMusicKitCanInitializeAndReleaseOnDevice() {
        compose.runOnUiThread {
            val app = compose.activity.application as ShufflerApplication
            val engine = app.platform.createEngine(compose.activity) {}
            engine.release()
        }
    }

    @Test fun signInRecoveryIsAccessibleInLandscapeWithLargeText() {
        show(840, 400, fontScale = 1.5f, connected = false)
        compose.onNodeWithText("Sign-in stuck?").performScrollTo().performClick()
        compose.onNodeWithText("If Apple Music keeps loading").assertIsDisplayed()
        compose.onNodeWithText("Open Apple Music").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Connect to Apple Music").performScrollTo().assertIsEnabled()
    }
}
