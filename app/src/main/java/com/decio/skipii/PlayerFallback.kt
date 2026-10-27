package com.decio.skipii

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Sign in to confirm you're not a bot": on some networks (mobile carriers share addresses)
 * YouTube refuses the extractor's anonymous ANDROID player request, and the extractor gives
 * up. The check is per client as well as per address, and that ANDROID answer only ever
 * supplies the video's details: its formats are SABR-only (no URLs), so every stream Skipii
 * plays comes from the extractor's second request, as VISIONOS.
 *
 * So when ANDROID is refused, VISIONOS is asked for the same details and its answer stands
 * in, minus its stream URLs, leaving the ANDROID slot exactly as it normally is. The streams
 * still come from the extractor's own VISIONOS request, as on any other network. (Its links
 * also serve whole files; ANDROID_VR and IOS links 403 past the first chunk without a
 * proof-of-origin token, so they're no use here.) Client values mirror NewPipeExtractor's
 * ClientsConstants; update them together.
 *
 * When VISIONOS is refused as well there is no third client to try (probed 2026-10-27):
 * TVHTML5_SIMPLY and MWEB still answer, but only with signed links whose n parameter needs
 * YouTube's player JavaScript, which extractor 0.26.5 can no longer run (dev neither, without
 * an external JS engine); WEB is SABR-only and ANDROID_VR links 403 past the first chunk.
 * [BotCheck] waits it out instead; the check comes and goes within minutes.
 */
object PlayerFallback {
    private const val CLIENT_NAME = "VISIONOS"
    private const val CLIENT_ID = "101"
    private const val CLIENT_VERSION = "1.02"
    private const val USER_AGENT = "com.google.ios.youtube/1.02 (RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X)"

    /** The extractor's ANDROID player request (it goes through the shorts "reel" endpoint). */
    private fun isReel(url: String) = "/youtubei/v1/reel/reel_item_watch" in url

    /** The extractor's own VISIONOS player request, the one every stream link comes from. */
    private fun isVisionOs(url: String) = "youtubei.googleapis.com/youtubei/v1/player" in url

    fun handles(url: String) = isReel(url) || isVisionOs(url)

    /** [responseBody] is YouTube's answer to [url] (one that [handles]); returns what the extractor gets. */
    fun substitute(url: String, requestBody: ByteArray?, responseBody: String?): String? =
        if (isReel(url)) substituteReel(requestBody, responseBody) else responseBody.also { noteVisionOs(url, it) }

    /**
     * The extractor ignores a refused VISIONOS answer: it reads the details from ANDROID and
     * finds no stream links but ANDROID's 360p combined one, which then played without a word.
     * Noted here so [StreamCache] can report it as the bot check it is (and [BotCheck] retry it).
     */
    private fun noteVisionOs(url: String, responseBody: String?) {
        val status = runCatching { JSONObject(responseBody.orEmpty()) }.getOrNull()?.optJSONObject("playabilityStatus")
        if (!isBotCheck(status)) return
        val videoId = url.toHttpUrlOrNull()?.queryParameter("id") ?: return
        Log.w("Skipii", "bot check on VISIONOS for $videoId; no stream links this time")
        BotCheck.noteRefused(videoId)
    }

    /**
     * [responseBody] is YouTube's answer to the reel request [requestBody]. Returns it as is
     * unless it's a bot check; then a stand-in `{"playerResponse": …}` from VISIONOS, or the
     * original if VISIONOS is refused too (the extractor then reports the bot check).
     */
    private fun substituteReel(requestBody: ByteArray?, responseBody: String?): String? {
        val reelStatus = runCatching { JSONObject(responseBody.orEmpty()) }.getOrNull()
            ?.optJSONObject("playerResponse")?.optJSONObject("playabilityStatus")
        if (requestBody == null || responseBody == null || !isBotCheck(reelStatus)) return responseBody
        val request = runCatching { JSONObject(String(requestBody)) }.getOrNull() ?: return responseBody
        val player = request.optJSONObject("playerRequest") ?: return responseBody
        val videoId = player.optString("videoId").ifEmpty { return responseBody }
        val answer = runCatching { ask(player, request.optJSONObject("context")?.optJSONObject("client")) }
            .onFailure { Log.w("Skipii", "bot check on ANDROID for $videoId; VISIONOS request failed", it) }
            .getOrNull() ?: return responseBody
        val status = answer.optJSONObject("playabilityStatus")?.optString("status")
        if (status != "OK") {
            Log.w("Skipii", "bot check on ANDROID for $videoId; VISIONOS said $status too")
            return responseBody
        }
        Log.i("Skipii", "bot check on ANDROID for $videoId; details from VISIONOS instead")
        answer.optJSONObject("streamingData")?.let { data ->
            listOf("formats", "adaptiveFormats").forEach { key -> data.optJSONArray(key)?.let(::dropUrls) }
        }
        return JSONObject().put("playerResponse", answer).toString()
    }

    /** [status] is a player answer's playabilityStatus. Same test as the extractor's checkPlayabilityStatus. */
    private fun isBotCheck(status: JSONObject?): Boolean =
        status != null && status.optString("status").equals("LOGIN_REQUIRED", ignoreCase = true) &&
            "a bot" in status.optString("reason")

    /** Leaves each format as ANDROID's are: described, but with nothing to fetch. */
    private fun dropUrls(formats: JSONArray) {
        for (i in 0 until formats.length()) {
            formats.optJSONObject(i)?.run { remove("url"); remove("signatureCipher"); remove("cipher") }
        }
    }

    private fun ask(player: JSONObject, original: JSONObject?): JSONObject {
        val visitorData = original?.optString("visitorData").orEmpty()
        val client = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            .put("clientScreen", "WATCH")
            .put("platform", "MOBILE")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice14,1")
            .put("osName", "visionOS")
            .put("osVersion", "25.6.0.23O471")
            .put("hl", original?.optString("hl")?.ifEmpty { null } ?: "en")
            .put("gl", original?.optString("gl")?.ifEmpty { null } ?: "US")
            .apply { if (visitorData.isNotEmpty()) put("visitorData", visitorData) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", player.getString("videoId"))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("X-Goog-Api-Format-Version", "2")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .apply { if (visitorData.isNotEmpty()) header("X-Goog-Visitor-Id", visitorData) }
            .build()
        Net.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return JSONObject(response.body!!.string())
        }
    }
}
