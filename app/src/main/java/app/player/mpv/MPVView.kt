package app.player.mpv

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.preference.PreferenceManager
import app.R
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.mpvFormat.MPV_FORMAT_FLAG
import `is`.xyz.mpv.MPVLib.mpvFormat.MPV_FORMAT_INT64
import `is`.xyz.mpv.MPVLib.mpvFormat.MPV_FORMAT_NONE
import `is`.xyz.mpv.MPVLib.mpvFormat.MPV_FORMAT_STRING
import kotlin.math.abs
import kotlin.reflect.KProperty

class MPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    fun initialize(configDir: String) {
        MPVLib.create(this.context)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        initOptions() // do this before init() so user-supplied config can override our choices
        MPVLib.init()
        // certain options are hardcoded:
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")

        holder.addCallback(this)
        observeProperties()
    }

    private fun initOptions() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.context)

        // hwdec
        val hwdec = if (sharedPreferences.getBoolean("hardware_decoding", true))
            "auto"
        else
            "no"

        // vo: set display fps as reported by android
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val disp = wm.defaultDisplay
        val refreshRate = disp.mode.refreshRate

        Log.v(TAG, "Display ${disp.displayId} reports FPS of $refreshRate")
        MPVLib.setOptionString("override-display-fps", refreshRate.toString())

        // set non-complex options
        data class Property(val preference_name: String, val mpv_option: String)

        val opts = arrayOf(
            Property("default_audio_language", "alang"),
            Property("default_subtitle_language", "slang"),

            // vo-related
            Property("video_scale", "scale"),
            Property("video_scale_param1", "scale-param1"),
            Property("video_scale_param2", "scale-param2"),

            Property("video_downscale", "dscale"),
            Property("video_downscale_param1", "dscale-param1"),
            Property("video_downscale_param2", "dscale-param2"),

            Property("video_tscale", "tscale"),
            Property("video_tscale_param1", "tscale-param1"),
            Property("video_tscale_param2", "tscale-param2")
        )

        for ((preference_name, mpv_option) in opts) {
            val preference = sharedPreferences.getString(preference_name, "")
            if (!preference.isNullOrBlank())
                MPVLib.setOptionString(mpv_option, preference)
        }

        // set more options

        val debandMode = sharedPreferences.getString("video_debanding", "")
        if (debandMode == "gradfun") {
            // lower the default radius (16) to improve performance
            MPVLib.setOptionString("vf", "gradfun=radius=12")
        } else if (debandMode == "gpu") {
            MPVLib.setOptionString("deband", "yes")
        }

        val vidsync = sharedPreferences.getString("video_sync", resources.getString(R.string.pref_video_interpolation_sync_default))
        MPVLib.setOptionString("video-sync", vidsync!!)

        if (sharedPreferences.getBoolean("video_interpolation", false))
            MPVLib.setOptionString("interpolation", "yes")

        if (sharedPreferences.getBoolean("gpudebug", false))
            MPVLib.setOptionString("gpu-debug", "yes")

        if (sharedPreferences.getBoolean("video_fastdecode", false)) {
            MPVLib.setOptionString("vd-lavc-fast", "yes")
            MPVLib.setOptionString("vd-lavc-skiploopfilter", "nonkey")
        }

        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", hwdec)
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${this.context.filesDir.path}/cacert.pem")
        MPVLib.setOptionString("input-default-bindings", "yes")
        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        //
        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        screenshotDir.mkdirs()
        MPVLib.setOptionString("screenshot-directory", screenshotDir.path)
    }

    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    fun playFileAlt(filePath: String) {
        MPVLib.command(arrayOf("loadfile", filePath, "append"))
    }

    // Called when back button is pressed, or app is shutting down
    fun destroy() {
        // Disable surface callbacks to avoid using unintialized mpv state
        holder.removeCallback(this)

        MPVLib.destroy()
    }

    fun onPointerEvent(event: MotionEvent): Boolean {
        assert(event.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(h) > 0)
                MPVLib.command(arrayOf("keypress", if (h < 0) "WHEEL_LEFT" else "WHEEL_RIGHT"))
            if (abs(v) > 0)
                MPVLib.command(arrayOf("keypress", if (v < 0) "WHEEL_DOWN" else "WHEEL_UP"))
            return true
        }
        return false
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE)
            return false
        if (KeyEvent.isModifierKey(event.keyCode))
            return false

        var mapped = KeyMapping.map.get(event.keyCode)
        if (mapped == null) {
            // Fallback to produced glyph
            if (!event.isPrintingKey) {
                if (event.repeatCount == 0)
                    Log.d(TAG, "Unmapped non-printable key ${event.keyCode}")
                return false
            }

            val ch = event.unicodeChar
            if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0)
                return false // dead key
            mapped = ch.toChar().toString()
        }

        if (event.repeatCount > 0)
            return true // eat event but ignore it, mpv has its own key repeat

        val mod: MutableList<String> = mutableListOf()
        event.isShiftPressed && mod.add("shift")
        event.isCtrlPressed && mod.add("ctrl")
        event.isAltPressed && mod.add("alt")
        event.isMetaPressed && mod.add("meta")

        val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        mod.add(mapped)
        MPVLib.command(arrayOf(action, mod.joinToString("+")))

        return true
    }

    private fun observeProperties() {
        // This observes all properties needed by MPVView, MPVActivity or other classes
        data class Property(val name: String, val format: Int = MPV_FORMAT_NONE)

        val p = arrayOf(
            Property("time-pos", MPV_FORMAT_INT64),
            Property("duration", MPV_FORMAT_INT64),
            Property("pause", MPV_FORMAT_FLAG),
            Property("track-list"),
            Property("video-params"),
            Property("playlist-pos", MPV_FORMAT_INT64),
            Property("playlist-count", MPV_FORMAT_INT64),
            Property("video-format"),
            Property("media-title", MPV_FORMAT_STRING),
            Property("metadata/by-key/Artist", MPV_FORMAT_STRING),
            Property("metadata/by-key/Album", MPV_FORMAT_STRING),
            Property("loop-playlist"),
            Property("loop-file"),
            Property("shuffle", MPV_FORMAT_FLAG),
            Property("hwdec-current"),
            Property("file-size", MPV_FORMAT_INT64)
        )

        for ((name, format) in p)
            MPVLib.observeProperty(name, format)
    }

    fun addObserver(o: MPVLib.EventObserver) {
        MPVLib.addObserver(o)
    }

    fun removeObserver(o: MPVLib.EventObserver) {
        MPVLib.removeObserver(o)
    }

    data class PlaylistItem(val index: Int, val filename: String, val title: String?)

    fun loadPlaylist(): MutableList<PlaylistItem> {
        val playlist = mutableListOf<PlaylistItem>()
        val count = MPVLib.getPropertyInt("playlist-count")!!
        for (i in 0 until count) {
            val filename = MPVLib.getPropertyString("playlist/$i/filename")!!
            val title = MPVLib.getPropertyString("playlist/$i/title")
            playlist.add(PlaylistItem(index = i, filename = filename, title = title))
        }
        return playlist
    }

    data class Chapter(val index: Int, val title: String?, val time: Double)

    fun loadChapters(): MutableList<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val count = MPVLib.getPropertyInt("chapter-list/count")!!
        for (i in 0 until count) {
            val title = MPVLib.getPropertyString("chapter-list/$i/title")
            val time = MPVLib.getPropertyDouble("chapter-list/$i/time")!!
            chapters.add(
                Chapter(
                    index = i,
                    title = title,
                    time = time
                )
            )
        }
        return chapters
    }

    private var filePath: String? = null

    // Property getters/setters

    var paused: Boolean?
        get() = MPVLib.getPropertyBoolean("pause")
        set(paused) = MPVLib.setPropertyBoolean("pause", paused!!)

    var timePos: Int?
        get() = MPVLib.getPropertyInt("time-pos")
        set(progress) = MPVLib.setPropertyInt("time-pos", progress!!)

    val hwdecActive: String
        get() = MPVLib.getPropertyString("hwdec-current") ?: "no"

    var playbackSpeed: Double?
        get() = MPVLib.getPropertyDouble("speed")
        set(speed) = MPVLib.setPropertyDouble("speed", speed!!)

    val estimatedVfFps: Double?
        get() = MPVLib.getPropertyDouble("estimated-vf-fps")

    val videoAspect: Double?
        get() = MPVLib.getPropertyDouble("video-params/aspect")

    // Commands

    fun cyclePause() = MPVLib.command(arrayOf("cycle", "pause"))
    fun cycleAudio() = MPVLib.command(arrayOf("cycle", "audio"))
    fun cycleSub() = MPVLib.command(arrayOf("cycle", "sub"))
    fun cycleHwdec() = MPVLib.command(arrayOf("cycle-values", "hwdec", "auto", "no"))

    fun cycleSpeed() {
        val speeds = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        val currentSpeed = playbackSpeed ?: 1.0
        val index = speeds.indexOfFirst { it > currentSpeed }
        playbackSpeed = speeds[if (index == -1) 0 else index]
    }

    fun getRepeat(): Int {
        return when (MPVLib.getPropertyString("loop-playlist") +
                MPVLib.getPropertyString("loop-file")) {
            "noinf" -> 2
            "infno" -> 1
            else -> 0
        }
    }

    fun cycleRepeat() {
        val state = getRepeat()
        when (state) {
            0, 1 -> {
                MPVLib.setPropertyString("loop-playlist", if (state == 1) "no" else "inf")
                MPVLib.setPropertyString("loop-file", if (state == 1) "inf" else "no")
            }

            2 -> MPVLib.setPropertyString("loop-file", "no")
        }
    }

    fun getShuffle(): Boolean {
        return MPVLib.getPropertyBoolean("shuffle")
    }

    fun changeShuffle(cycle: Boolean, value: Boolean = true) {
        // Use the 'shuffle' property to store the shuffled state, since changing
        // it at runtime doesn't do anything.
        val state = getShuffle()
        val newState = if (cycle) state.xor(value) else value
        if (state == newState)
            return
        MPVLib.command(arrayOf(if (newState) "playlist-shuffle" else "playlist-unshuffle"))
        MPVLib.setPropertyBoolean("shuffle", newState)
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", "gpu")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}