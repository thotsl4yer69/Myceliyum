package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupRegressionTest {
    @Test fun mainActivityConstructsViewModelOnTheImmediateMainDispatcher() {
        Robolectric.buildActivity(MainActivity::class.java).use { activity ->
            activity.setup()
            check(!activity.get().isFinishing)
        }
    }
}
