package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateParserTest {
    @Test
    fun parsesGithubReleaseAndPrefersEtaApk() {
        val offer = AppUpdateParser.parseLatestRelease(
            """
            {
              "tag_name":"v5.2.3",
              "name":"5.2.3",
              "body":"bug fixes",
              "html_url":"https://github.com/y2485871697/Eta/releases/tag/v5.2.3",
              "draft":false,
              "prerelease":false,
              "assets":[
                {
                  "name":"notes.txt",
                  "browser_download_url":"https://example.com/notes.txt"
                },
                {
                  "name":"app-release.apk",
                  "browser_download_url":"https://github.com/y2485871697/Eta/releases/download/v5.2.3/app-release.apk"
                },
                {
                  "name":"eta-5.2.3.apk",
                  "browser_download_url":"https://github.com/y2485871697/Eta/releases/download/v5.2.3/eta-5.2.3.apk"
                }
              ]
            }
            """.trimIndent(),
        )!!

        assertEquals("5.2.3", offer.versionName)
        assertEquals("v5.2.3", offer.tagName)
        assertEquals("bug fixes", offer.notes)
        assertEquals("eta-5.2.3.apk", offer.apkName)
        assertEquals(
            "https://github.com/y2485871697/Eta/releases/download/v5.2.3/eta-5.2.3.apk",
            offer.apkUrl,
        )
    }

    @Test
    fun skipsPrereleaseAndDraft() {
        assertNull(
            AppUpdateParser.parseLatestRelease(
                """{"tag_name":"v5.3.0","prerelease":true,"assets":[]}""",
            ),
        )
        assertNull(
            AppUpdateParser.parseLatestRelease(
                """{"tag_name":"v5.3.0","draft":true,"assets":[]}""",
            ),
        )
    }
}
