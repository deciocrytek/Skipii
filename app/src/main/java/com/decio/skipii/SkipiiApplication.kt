package com.decio.skipii

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe

class SkipiiApplication : Application(), ImageLoaderFactory {

    /** The Shorts feed, players and bank: one per process, outliving any window. */
    val shorts by lazy { ShortsState(this, kotlinx.coroutines.MainScope()) }

    override fun onCreate() {
        super.onCreate()
        // Before anything that could crash, so its stack trace is kept (Settings › About › Crash report).
        CrashLog.install(this)
        // Then the notebook every log line goes into (Diagnostics.kt), read by the problem report.
        AppLog.init(this)
        // First: everything below may already word something (str()).
        AppLanguage.init(this)
        NewPipe.init(NewPipeDownloader.instance)
        // The iOS client only adds HLS livestream formats; Skipii plays VOD, so skip that request.
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false)
        ThemeSettings.init(this)
        QualityPreference.init(this)
        AudioTrackPreference.init(this)
        PlaybackSpeed.init(this)
        AudioOnly.init(this)
        PlayerControls.init(this)
        FullscreenZoom.init(this)
        FullscreenSwipes.init(this)
        SeekStep.init(this)
        ChaptersStayOpen.init(this)
        BackgroundPlay.init(this)
        AutoPip.init(this)
        LiveControls.init(this)
        CaptionSettings.init(this)
        CaptionTranslator.refresh()
        SearchHistory.init(this)
        ResumeStore.init(this)
        WatchedStore.init(this)
        RecentVideos.init(this)
        FeedFilters.init(this)
        SubscriptionGroups.init(this)
        Downloads.init(this)
        // A fresh process: widget buttons drawn by one Android killed may still expect a running player; redraw them.
        WidgetUpdater.update(this)
        SavedSession.init(this)
        Account.init(this)
        Bells.init(this)
        NewVideos.schedule(this)
        SponsorBlock.init(this)
        ShortsSetting.init(this)
        ShortsBank.init(this)
        AppUpdate.init(this)
        // Compose state kept in an object must be created here, on the main thread, before any
        // composition. An object first touched on a background thread (BotCheck from PlayerFallback's
        // request) made its state after the player's composition snapshot was taken, and reading it
        // there crashed: "Reading a state that was created after the snapshot was taken" (2026-11-21).
        listOf<Any>(BotCheck, StreamRecovery, ChannelNames, Dismissed, IncomingLinks, StatusBarOverVideo, SharedVideoTexture, Downloads, WatchedStore, RecentVideos, FeedFilters, SubscriptionGroups, OpenDownloads)
            .forEach { it.hashCode() }
        // Stream URLs are bound to the network they were fetched on: forget them on a switch.
        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                private var last: android.net.Network? = null
                /** Lost with nothing in its place yet: the next network to come up is a change even if it's the same one. */
                private var down = false

                override fun onAvailable(network: android.net.Network) {
                    // Any network that wasn't there before, the first one too (the app may have started offline).
                    val changed = last != network || down
                    if (last != null && changed) StreamCache.invalidateAll()
                    if (last != network || down) AppLog.i("Skipii", "network: ${if (last == null) "connected" else if (last == network) "back" else "switched"}")
                    // Connections on the old network are dead; a load stuck on one starts over now.
                    if (changed) Net.networkChanged(up = true, first = last == null)
                    last = network
                    down = false
                }

                override fun onLost(network: android.net.Network) {
                    if (network == last) {
                        AppLog.i("Skipii", "network: lost")
                        down = true
                        Net.networkChanged(up = false)
                    }
                }
            },
        )
    }

    // Thumbnails are in every list and the player; a short crossfade replaces pop-in.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .build()
}
