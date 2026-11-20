package com.kaiser.rivet.replay

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ReplayVideoEncoder {
    fun encode(
        story: ReplayStory,
        frames: ReplayFrames,
        watermark: Boolean,
        output: File,
        checkActive: () -> Unit,
    ) {
        val schedule = ReplaySchedule.from(story.beats.map { it.holdMs })
        try {
            require(schedule.spans.isNotEmpty())
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            for ((width, height) in listOf(1080 to 1920, 720 to 1280)) {
                for (info in codecs) {
                    checkActive()
                    val caps = try { info.getCapabilitiesForType(MIME) }
                        catch (_: IllegalArgumentException) { continue }
                    val video = caps.videoCapabilities ?: continue
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats ||
                        !video.areSizeAndRateSupported(width, height, schedule.fps.toDouble())) continue
                    val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(width * height))
                        setInteger(MediaFormat.KEY_FRAME_RATE, schedule.fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    }
                    try {
                        encodeWith(info.name, format, width, height, schedule, story, frames,
                            watermark, output, checkActive)
                        return
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        output.delete()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            output.delete()
        }
        throw ReplayVideoUnavailable()
    }

    private fun encodeWith(
        name: String, format: MediaFormat, width: Int, height: Int, schedule: ReplaySchedule,
        story: ReplayStory, frames: ReplayFrames, watermark: Boolean, output: File,
        checkActive: () -> Unit,
    ) {
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            try {
                EncoderSurface().use { surface ->
                    surface.open(input, width, height)
                    codec.start()
                    val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val track = VideoTrack(codec, muxer)
                        schedule.spans.forEach { span ->
                            checkActive()
                            val bitmap = frames.render(story.beats[span.beatIndex], watermark, width, height)
                            try { surface.load(bitmap) } finally { bitmap.recycle() }
                            repeat(span.count) { offset ->
                                checkActive()
                                track.drain()
                                surface.draw(schedule.timeUs(span.firstFrame + offset))
                            }
                        }
                        codec.signalEndOfInputStream()
                        val deadline = SystemClock.elapsedRealtime() + 10_000L
                        while (!track.drain(waitUs = 10_000L)) {
                            checkActive()
                            check(SystemClock.elapsedRealtime() < deadline) { "Video encoder stalled" }
                        }
                        track.finish(schedule.durationUs)
                        muxer.stop()
                    } finally { runCatching { muxer.release() } }
                }
            } finally { input.release() }
        } finally { runCatching { codec.release() } }
    }

    private class VideoTrack(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val buffer = MediaCodec.BufferInfo()
        private var track = -1
        private var lastUs = -1L

        fun drain(waitUs: Long = 0L): Boolean {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(buffer, waitUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track == -1)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    else -> if (index >= 0) {
                        val ended = buffer.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try {
                            if (buffer.size > 0 && buffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val data = checkNotNull(codec.getOutputBuffer(index))
                                buffer.flags = buffer.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                                muxer.writeSampleData(track, data, buffer)
                                lastUs = buffer.presentationTimeUs
                            }
                        } finally { codec.releaseOutputBuffer(index, false) }
                        if (ended) return true
                    }
                }
            }
        }

        fun finish(durationUs: Long) {
            check(lastUs in 0 until durationUs)
            buffer.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(track, ByteBuffer.allocate(0), buffer)
        }
    }

    private class EncoderSurface : Closeable {
        private var display = EGL_NO_DISPLAY
        private var context = EGL_NO_CONTEXT
        private var window = EGL_NO_SURFACE
        private val vertices = ByteBuffer.allocateDirect(16 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
                rewind()
            }

        fun open(input: Surface, width: Int, height: Int) {
            display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
            check(display != EGL_NO_DISPLAY && eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                0x3142, EGL_TRUE, EGL_NONE) // EGL_RECORDABLE_ANDROID
            check(eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = configs[0]
            context = eglCreateContext(display, config, EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE), 0)
            window = eglCreateWindowSurface(display, config, input, intArrayOf(EGL_NONE), 0)
            check(context != EGL_NO_CONTEXT && window != EGL_NO_SURFACE)
            check(eglMakeCurrent(display, window, window, context))

            val program = glCreateProgram()
            listOf(
                GL_VERTEX_SHADER to "attribute vec4 position; attribute vec2 uv; varying vec2 coord;" +
                    "void main(){gl_Position=position;coord=uv;}",
                GL_FRAGMENT_SHADER to "precision mediump float; varying vec2 coord; uniform sampler2D frame;" +
                    "void main(){gl_FragColor=texture2D(frame,coord);}",
            ).forEach { (type, source) ->
                val shader = glCreateShader(type)
                val compiled = IntArray(1)
                glShaderSource(shader, source)
                glCompileShader(shader)
                glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
                check(compiled[0] == GL_TRUE)
                glAttachShader(program, shader)
                glDeleteShader(shader)
            }
            glLinkProgram(program)
            glGetProgramiv(program, GL_LINK_STATUS, count, 0)
            check(count[0] == GL_TRUE)
            glUseProgram(program)
            glDeleteProgram(program)
            listOf("position", "uv").forEachIndexed { index, attribute ->
                val location = glGetAttribLocation(program, attribute)
                // GL stores the attribute buffers, so vertices stays alive with the surface.
                glVertexAttribPointer(location, 2, GL_FLOAT, false, 16, vertices.position(index * 2))
                glEnableVertexAttribArray(location)
            }
            glUniform1i(glGetUniformLocation(program, "frame"), 0)
            val texture = IntArray(1)
            glGenTextures(1, texture, 0)
            glBindTexture(GL_TEXTURE_2D, texture[0])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glViewport(0, 0, width, height)
            check(glGetError() == GL_NO_ERROR)
        }

        fun load(bitmap: Bitmap) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
            check(glGetError() == GL_NO_ERROR)
        }

        fun draw(timeUs: Long) {
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            check(glGetError() == GL_NO_ERROR)
            check(EGLExt.eglPresentationTimeANDROID(display, window, timeUs * 1000L))
            check(eglSwapBuffers(display, window))
        }

        override fun close() {
            if (display == EGL_NO_DISPLAY) return
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            if (window != EGL_NO_SURFACE) eglDestroySurface(display, window)
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
            eglReleaseThread()
            eglTerminate(display)
        }
    }

    private companion object { const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC }
}

internal class ReplayVideoUnavailable : Exception("This phone can't encode video here — try GIF.")
