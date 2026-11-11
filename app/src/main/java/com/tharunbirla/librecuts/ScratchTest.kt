package com.tharunbirla.librecuts
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
@OptIn(markerClass = [UnstableApi::class])
class ScratchTest {
    fun test(s1: MediaSource, s2: MediaSource) {
        MergingMediaSource(true, s1, s2)
        MergingMediaSource(true, true, s1, s2)
    }
}
