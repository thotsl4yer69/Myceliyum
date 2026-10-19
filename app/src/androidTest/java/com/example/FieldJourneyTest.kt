package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FieldJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun coldLaunchCatalogueAndLogWorkWithoutLocationPermission() {
        compose.onNodeWithTag("accept_warning_button").performClick()
        compose.onNodeWithTag("app_bottom_nav").assertIsDisplayed()
        compose.onNodeWithTag("nav_search").performClick()
        compose.waitForIdle()
        capture("catalogue")
        compose.onNodeWithTag("nav_sightings").performClick()
        compose.onNodeWithTag("add_sighting_fab").performClick()
        compose.onNodeWithTag("submit_sighting_button").assertIsDisplayed()
        capture("sighting")
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { out ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
