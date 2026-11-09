package dev.hjun.checkagain

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * 마이크가 켜질 때와 꺼질 때 나는 짧은 효과음. 소리 파일 없이 사인파로 만든다.
 * 켜짐은 낮은 음에서 높은 음으로 올라가고, 꺼짐은 높은 음에서 낮은 음으로 내려간다.
 */
object Earcon {

    private const val RATE = 44100
    private const val NOTE_MS = 80
    private const val VOLUME = 0.4f

    // 켜짐 소리가 나는 총 길이. 마이크가 이 소리를 답으로 듣지 않도록 호출하는 쪽에서 쓴다.
    const val ON_MS = NOTE_MS * 2

    fun on() = play(floatArrayOf(660f, 990f))

    fun off() = play(floatArrayOf(880f, 550f))

    private fun play(freqs: FloatArray) {
        Thread {
            runCatching {
                val per = RATE * NOTE_MS / 1000
                val pcm = ShortArray(per * freqs.size)
                val fade = RATE * 8 / 1000
                freqs.forEachIndexed { n, hz ->
                    for (i in 0 until per) {
                        val edge = minOf(i, per - 1 - i).coerceAtMost(fade).toFloat() / fade
                        val v = sin(2.0 * PI * hz * i / RATE).toFloat() * VOLUME * edge
                        pcm[n * per + i] = (v * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val bytes = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((NOTE_MS * freqs.size + 80).toLong())
                track.release()
            }
        }.start()
    }
}
