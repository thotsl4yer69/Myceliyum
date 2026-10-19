package com.example.update

import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private val valid = ReleaseVersion(7201, "7.2", apkUrl = "https://github.com/thotsl4yer69/Myceliyum/releases/download/latest/app-debug.apk", sha256 = "a".repeat(64), sourceCommit = "b".repeat(40))
    @Test fun completeMetadataIsAccepted() { assertTrue(ReleasePolicy.validMetadata(valid)) }
    @Test fun oldAndIncompleteReleasesAreBlocked() {
        assertFalse(ReleasePolicy.validMetadata(valid.copy(versionCode = 66)))
        assertFalse(ReleasePolicy.validMetadata(valid.copy(sha256 = "")))
        assertFalse(ReleasePolicy.validMetadata(valid.copy(sourceCommit = "")))
    }
    @Test fun otherHostsAndInsecureUrlsAreRejected() {
        assertFalse(ReleasePolicy.validMetadata(valid.copy(apkUrl = "https://example.com/app.apk")))
        assertFalse(ReleasePolicy.validMetadata(valid.copy(apkUrl = valid.apkUrl.replace("https:", "http:"))))
    }
}
