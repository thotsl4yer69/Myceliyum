package com.example.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    private fun remote(code: Int) = ReleaseVersion(versionCode = code, versionName = "7.0")

    @Test
    fun `newer remote build is an update`() {
        assertTrue(VersionCompare.isUpdateAvailable(currentVersionCode = 10, remote = remote(11)))
    }

    @Test
    fun `equal build is not an update`() {
        assertFalse(VersionCompare.isUpdateAvailable(currentVersionCode = 10, remote = remote(10)))
    }

    @Test
    fun `older remote build is not an update`() {
        assertFalse(VersionCompare.isUpdateAvailable(currentVersionCode = 10, remote = remote(9)))
    }

    @Test
    fun `null remote is never an update`() {
        assertFalse(VersionCompare.isUpdateAvailable(currentVersionCode = 1, remote = null))
    }
}
