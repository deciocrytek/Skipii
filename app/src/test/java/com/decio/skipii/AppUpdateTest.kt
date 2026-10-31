package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test
    fun latestReleaseParsed() {
        // Real GitHub /releases/latest answer (v0.5.54).
        val release = AppUpdate.parseRelease(fixture("release_latest.json"))!!
        assertEquals("0.5.54", release.version)
        assertEquals("https://github.com/deciocrytek/Skipii/releases/download/v0.5.54/Skipii.apk", release.apkUrl)
        assertEquals(3711772L, release.size)
        assertEquals("fd7c4683a6c37bbce0d22f258e4bcf752b3ffc9342a07586c9f26bb56dcde9dd", release.sha256)
    }

    @Test
    fun releaseWithoutApkIsNothing() {
        assertNull(AppUpdate.parseRelease(JSONObject().put("tag_name", "v9.9.9").put("assets", org.json.JSONArray())))
    }

    @Test
    fun notesKeepHeadingsAndBulletsOnly() {
        val sections = AppUpdate.parseNotes(
            """
            ### New
            - Tap **Update** to see what's new ([#30](https://github.com/x/y/issues/30)).
            - A long line that
              carries on here.
            ### Fixed
            * `Shorts` reopen faster.

            ---

            Install Skipii.apk on Android.
            - not a note
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                AppUpdate.NoteSection("New", listOf("Tap Update to see what's new (#30).", "A long line that carries on here.")),
                AppUpdate.NoteSection("Fixed", listOf("Shorts reopen faster.")),
            ),
            sections,
        )
        // The old releases' install line alone is no notes at all.
        assertTrue(AppUpdate.parseNotes("Install Skipii.apk on Android (allow installs from your browser or file manager).").isEmpty())
    }

    @Test
    fun releasesListGathersNotesSinceThisBuild() {
        fun release(version: String, body: String, apk: Boolean = true, prerelease: Boolean = false) = fixture("release_latest.json")
            .put("tag_name", "v$version").put("body", body).put("prerelease", prerelease)
            .apply { if (!apk) put("assets", org.json.JSONArray()) }
        val list = org.json.JSONArray()
            .put(release("0.5.70", "- beta", prerelease = true))
            .put(release("0.5.68", "### New\n- Sixty-eight"))
            .put(release("0.5.67", "- Sixty-seven"))
            .put(release("0.5.66", "Install Skipii.apk on Android."))
            .put(release("0.5.65", "- Already installed"))
        val latest = AppUpdate.parseReleases(list, current = "0.5.65")!!
        assertEquals("0.5.68", latest.version)
        assertEquals(listOf("0.5.68", "0.5.67"), latest.notes.map { it.version })
        assertEquals(AppUpdate.NoteSection("New", listOf("Sixty-eight")), latest.notes[0].sections.single())
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdate.compareVersions("0.5.55", "0.5.54") > 0)
        assertTrue(AppUpdate.compareVersions("0.5.100", "0.5.99") > 0)
        assertTrue(AppUpdate.compareVersions("0.6", "0.5.54") > 0)
        assertTrue(AppUpdate.compareVersions("0.5.54", "0.5.54") == 0)
        assertTrue(AppUpdate.compareVersions("0.5.9", "0.5.54") < 0)
    }
}
