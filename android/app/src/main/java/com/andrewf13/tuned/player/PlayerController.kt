package com.andrewf13.tuned.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

data class StreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val hdr: String? = null,
    val fps: Float = 0f,
    val audio: String? = null,
)

/** Live network numbers for the info bar. */
data class StreamStats(val mbps: Float, val bufferSec: Float)

@OptIn(UnstableApi::class)
class PlayerController(context: Context) {

    private val main = Handler(Looper.getMainLooper())

    val exo: ExoPlayer

    private val _info = MutableStateFlow(StreamInfo())
    val info: StateFlow<StreamInfo> = _info
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering

    private var currentUrl: String? = null
    private var retries = 0

    // Frame timestamps gathered on the playback thread to measure the real frame rate;
    // live MPEG-TS rarely declares one.
    private val ptsLock = Any()
    private val pts = ArrayList<Long>(64)
    @Volatile private var measuring = false
    @Volatile private var generation = 0

    // Bytes received from the provider, for the bitrate readout.
    private val bytesIn = AtomicLong()
    private var lastBytes = 0L
    private var lastSampleAt = 0L
    private var smoothedMbps = 0f
    private val meter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesIn.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    // Volume boost: a fixed audio session so a LoudnessEnhancer can sit on it.
    // Works on audio the Shield decodes (AAC, MP2, stereo); Dolby passed through to a
    // soundbar/AVR is untouched by design.
    private val audioSessionId =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    private val loudness: LoudnessEnhancer? = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()

    init {
        val http = DefaultHttpDataSource.Factory()
            .setTransferListener(meter)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        // Small start buffer so channel changes feel quick.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build()

        exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .build()
        // We switch the display ourselves; don't let ExoPlayer ask for "seamless only".
        exo.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        exo.playWhenReady = true
        exo.audioSessionId = audioSessionId

        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = updateInfo()
            override fun onVideoSizeChanged(videoSize: VideoSize) = updateInfo()
            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> { retries = 0; _error.value = null }
                    // A live stream "ending" means the provider dropped us: reconnect.
                    Player.STATE_ENDED -> retry()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
                }
                if (!retry()) _error.value = describe(error)
            }
        })

        exo.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ -> onFrame(presentationTimeUs) }
    }

    fun play(url: String) {
        currentUrl = url
        retries = 0
        _error.value = null
        startMeasuring()
        smoothedMbps = 0f
        _info.value = StreamInfo()
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.play()
    }

    /** Re-tune the last stream, e.g. after returning from the Shield home screen. */
    fun resume() { currentUrl?.let { play(it) } }

    fun stop() { exo.stop() }

    fun release() {
        loudness?.release()
        exo.release()
    }

    fun setBoostDb(db: Int) {
        runCatching {
            loudness?.setTargetGain(db * 100)
            loudness?.enabled = db > 0
        }
    }

    /** Call about once a second while the numbers are on screen. */
    fun sampleStats(): StreamStats {
        val now = SystemClock.elapsedRealtime()
        val b = bytesIn.get()
        if (lastSampleAt > 0 && now > lastSampleAt) {
            val mbps = (b - lastBytes) * 8f / ((now - lastSampleAt) * 1000f)
            smoothedMbps = if (smoothedMbps == 0f) mbps else smoothedMbps * 0.6f + mbps * 0.4f
        }
        lastBytes = b
        lastSampleAt = now
        return StreamStats(smoothedMbps, exo.totalBufferedDuration / 1000f)
    }

    private fun retry(): Boolean {
        val url = currentUrl ?: return false
        if (retries >= 3) return false
        retries++
        main.postDelayed({ if (currentUrl == url) { exo.prepare(); exo.play() } }, 1_500L * retries)
        return true
    }

    private fun startMeasuring() {
        synchronized(ptsLock) { pts.clear(); generation++; measuring = true }
    }

    private fun onFrame(ptsUs: Long) {
        if (!measuring) return
        synchronized(ptsLock) {
            pts.add(ptsUs)
            if (pts.size < 56) return
            val fps = FrameRates.fromTimestamps(pts.drop(8))
            measuring = false
            val gen = generation
            if (fps > 0f) main.post { if (gen == generation) _info.value = _info.value.copy(fps = fps) }
        }
    }

    private fun updateInfo() {
        val v = exo.videoFormat
        val a = exo.audioFormat
        val declaredFps = v?.frameRate?.takeIf { it > 0f }?.let { FrameRates.snap(it) }
        _info.value = _info.value.copy(
            width = v?.width?.takeIf { it > 0 } ?: exo.videoSize.width,
            height = v?.height?.takeIf { it > 0 } ?: exo.videoSize.height,
            videoCodec = v?.let { codecName(it) },
            hdr = v?.let { hdrName(it) },
            fps = if (_info.value.fps > 0f) _info.value.fps else declaredFps ?: 0f,
            audio = a?.let { audioName(it) },
        )
    }

    private fun codecName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_DOLBY_VISION -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    private fun hdrName(f: Format): String? {
        val codecs = f.codecs.orEmpty()
        return when {
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dva") -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun audioName(f: Format): String {
        val name = when (f.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "Audio"
        }
        val ch = when (f.channelCount) { Format.NO_VALUE, 0 -> ""; 1 -> " mono"; 2 -> " stereo"; 6 -> " 5.1"; 8 -> " 7.1"; else -> " ${f.channelCount}ch" }
        return name + ch
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this channel (it may be offline, or you've hit your connection limit)."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the provider."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This stream's format isn't supported. Try switching Stream format in Settings."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "The Shield can't decode this stream."
        else -> "Playback failed (${e.errorCodeName})."
    }
}
