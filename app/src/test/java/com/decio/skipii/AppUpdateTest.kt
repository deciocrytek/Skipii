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
    fun versionsCompareNumerically() {
        assertTrue(AppUpdate.compareVersions("0.5.55", "0.5.54") > 0)
        assertTrue(AppUpdate.compareVersions("0.5.100", "0.5.99") > 0)
        assertTrue(AppUpdate.compareVersions("0.6", "0.5.54") > 0)
        assertTrue(AppUpdate.compareVersions("0.5.54", "0.5.54") == 0)
        assertTrue(AppUpdate.compareVersions("0.5.9", "0.5.54") < 0)
    }
}
