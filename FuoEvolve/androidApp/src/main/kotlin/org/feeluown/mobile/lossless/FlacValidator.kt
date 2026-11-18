package org.feeluown.mobile.lossless

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AppLogger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.coroutines.coroutineContext

/**
 * FLAC 容器与疑似有损转码特征校验。
 *
 * 注意：容器与频谱启发式检查不能证明文件来自无损母带，只能验证 FLAC 结构并识别部分明显的有损转码特征。
 *
 * 分两层：
 * 1. 容器层：解析 FLAC 的 fLaC 魔数与 STREAMINFO，确认采样率/位深/时长合法；
 * 2. 频谱层：用系统解码器解出 PCM，做 FFT 检查高频是否存在“断崖”。
 *    部分有损转码会在高频区域出现明显低通悬崖；该特征只能用于启发式筛查，
 *    不同编码器、音源与母带会产生不同频谱，不能据此证明或否定母带的真实来源。
 */
class FlacValidator {
    /** 从文件头解析 FLAC 容器信息，失败返回 null。 */
    fun readStreamInfo(file: File): FlacStreamInfo? = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(STREAMINFO_READ_BYTES)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            parseStreamInfo(header.copyOf(read))
        }
    }.getOrNull()

    /** 校验 FLAC 容器，并尽可能执行频谱启发式检查。 */
    suspend fun validate(file: File): LosslessValidation = withContext(Dispatchers.IO) {
        val streamInfo = readStreamInfo(file)
            ?: return@withContext LosslessValidation(
                accepted = false,
                reason = "不是有效的 FLAC 文件",
                streamInfo = null,
                spectral = null,
            )
        if (streamInfo.sampleRate < MIN_SAMPLE_RATE) {
            return@withContext LosslessValidation(
                accepted = false,
                reason = "采样率过低：${streamInfo.sampleRate}Hz",
                streamInfo = streamInfo,
                spectral = null,
            )
        }
        if (streamInfo.bitsPerSample !in MIN_BITS_PER_SAMPLE..MAX_BITS_PER_SAMPLE) {
            return@withContext LosslessValidation(
                accepted = false,
                reason = "位深异常：${streamInfo.bitsPerSample}bit",
                streamInfo = streamInfo,
                spectral = null,
            )
        }
        if (streamInfo.durationSeconds in 0.1..MIN_DURATION_SECONDS) {
            return@withContext LosslessValidation(
                accepted = false,
                reason = "音频过短，疑似片段",
                streamInfo = streamInfo,
                spectral = null,
            )
        }

        val spectral = try {
            analyzeSpectrum(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLogger.w(TAG, "频谱分析失败 file=${file.name}", error)
            null
        }
        if (spectral?.looksTranscoded == true) {
            return@withContext LosslessValidation(
                accepted = false,
                reason = "检测到有损转码特征（${"%.0f".format(spectral.cliffHz / 1000)}kHz 断崖 " +
                    "${"%.0f".format(spectral.maxCliffDb)}dB）",
                streamInfo = streamInfo,
                spectral = spectral,
            )
        }
        LosslessValidation(
            accepted = true,
            reason = if (spectral == null) {
                "FLAC 容器合法（未完成频谱检查，不能据此证明原始母带无损）"
            } else {
                "FLAC 容器合法，未发现明显有损转码特征（启发式检查）"
            },
            streamInfo = streamInfo,
            spectral = spectral,
        )
    }

    /**
     * 解码一段音频并分析频谱。
     *
     * 从音频 35% 处开始取约 8 秒，避开前奏静音；解码器不可用或输出格式异常时返回 null，
     * 此时只做容器校验，避免误杀真正无损。
     */
    private suspend fun analyzeSpectrum(file: File): SpectralAnalysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return null
            val selectedFormat = inputFormat
            extractor.selectTrack(trackIndex)
            val durationUs = selectedFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            if (durationUs > SEEK_MIN_DURATION_US) {
                extractor.seekTo((durationUs * SEEK_FRACTION).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            runCatching {
                selectedFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }
            decoder.configure(selectedFormat, null, null, 0)
            decoder.start()

            val analyzer = SpectralAnalyzer()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            while (analyzer.frameCount < ANALYZER_MAX_FRAMES && System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        analyzer.configure(decoder.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            analyzer.feed(outputBuffer)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
            return analyzer.result()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class SpectralAnalyzer {
        private var sampleRate = 0
        private var channels = 1
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var bytesPerSample = 2
        private var pending = FloatArray(FFT_SIZE * 2)
        private var pendingSize = 0
        private val accumulator = DoubleArray(FFT_SIZE / 2)
        private val real = FloatArray(FFT_SIZE)
        private val imaginary = FloatArray(FFT_SIZE)
        private val window = FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))).toFloat()
        }

        var frameCount = 0
            private set

        fun configure(format: MediaFormat) {
            sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            encoding = runCatching {
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
            bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
        }

        fun feed(buffer: ByteBuffer) {
            if (sampleRate <= 0) return
            val byteBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            val frameBytes = bytesPerSample * channels
            val availableFrames = byteBuffer.remaining() / frameBytes
            for (frameIndex in 0 until availableFrames) {
                var mono = 0.0
                for (channel in 0 until channels) {
                    mono += readSample(byteBuffer)
                }
                append((mono / channels).toFloat())
            }
        }

        private fun readSample(buffer: ByteBuffer): Double = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble()
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128.0
            else -> {
                val low = buffer.get().toInt() and 0xFF
                val high = buffer.get().toInt()
                ((high shl 8) or low).toShort() / 32_768.0
            }
        }

        private fun append(sample: Float) {
            if (pendingSize >= pending.size) {
                val grown = FloatArray(pending.size * 2)
                pending.copyInto(grown, 0, 0, pendingSize)
                pending = grown
            }
            pending[pendingSize++] = sample
        }

        fun result(): SpectralAnalysis? {
            flushFrames()
            if (frameCount < MIN_ANALYZED_FRAMES || sampleRate <= 0) return null
            val binHz = sampleRate.toDouble() / FFT_SIZE
            val nyquist = sampleRate / 2.0
            val midBandDb = bandDb(binHz, 5_000.0, 8_000.0)
            val referenceDb = bandDb(binHz, 15_000.0, 18_000.0)
            val highBandDb = bandDb(binHz, 20_500.0, min(21_800.0, nyquist * 0.98))
            var maxCliff = 0.0
            var cliffHz = 0.0
            var frequency = 14_000.0
            while (frequency <= min(21_000.0, nyquist - 800.0)) {
                val left = bandDb(binHz, frequency - 600.0, frequency - 100.0)
                val right = bandDb(binHz, frequency + 100.0, frequency + 600.0)
                val drop = left - right
                if (drop > maxCliff) {
                    maxCliff = drop
                    cliffHz = frequency
                }
                frequency += 250.0
            }
            val sharpCliff = maxCliff >= TRANSCODE_CLIFF_DB
            val narrowband = referenceDb - midBandDb >= NARROWBAND_DEFICIT_DB &&
                maxCliff >= NARROWBAND_CLIFF_DB
            return SpectralAnalysis(
                sampleRate = sampleRate,
                analyzedFrames = frameCount,
                midBandDb = midBandDb,
                highBandDb = highBandDb,
                maxCliffDb = maxCliff,
                cliffHz = cliffHz,
                looksTranscoded = sharpCliff || narrowband,
            )
        }

        /** 按不重叠窗口切帧并累计功率谱。 */
        private fun flushFrames() {
            while (pendingSize >= FFT_SIZE && frameCount < ANALYZER_MAX_FRAMES) {
                analyzeFrame()
                pending.copyInto(pending, 0, FFT_SIZE, pendingSize)
                pendingSize -= FFT_SIZE
            }
        }

        private fun analyzeFrame() {
            if (sampleRate <= 0) return
            for (index in 0 until FFT_SIZE) {
                real[index] = pending[index] * window[index]
                imaginary[index] = 0f
            }
            fft(real, imaginary)
            for (bin in accumulator.indices) {
                val re = real[bin].toDouble()
                val im = imaginary[bin].toDouble()
                accumulator[bin] += re * re + im * im
            }
            frameCount++
        }

        private fun bandDb(binHz: Double, fromHz: Double, toHz: Double): Double {
            if (binHz <= 0 || toHz <= fromHz) return -180.0
            val fromBin = (fromHz / binHz).toInt().coerceIn(1, accumulator.lastIndex)
            val toBin = (toHz / binHz).toInt().coerceIn(fromBin + 1, accumulator.size - 1)
            var power = 0.0
            for (bin in fromBin until toBin) {
                power += accumulator[bin]
            }
            power /= (toBin - fromBin).coerceAtLeast(1) * frameCount.coerceAtLeast(1)
            return if (power <= 0.0) -180.0 else 10.0 * log10(power)
        }

        /** 标准迭代版 radix-2 FFT。 */
        private fun fft(re: FloatArray, im: FloatArray) {
            val size = re.size
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tempRe = re[i]
                    re[i] = re[j]
                    re[j] = tempRe
                    val tempIm = im[i]
                    im[i] = im[j]
                    im[j] = tempIm
                }
            }
            var length = 2
            while (length <= size) {
                val angle = -2.0 * PI / length
                val wRe = cos(angle)
                val wIm = sin(angle)
                var start = 0
                while (start < size) {
                    var currentRe = 1.0
                    var currentIm = 0.0
                    for (offset in 0 until length / 2) {
                        val evenIndex = start + offset
                        val oddIndex = evenIndex + length / 2
                        val oddRe = re[oddIndex] * currentRe - im[oddIndex] * currentIm
                        val oddIm = re[oddIndex] * currentIm + im[oddIndex] * currentRe
                        val evenRe = re[evenIndex].toDouble()
                        val evenIm = im[evenIndex].toDouble()
                        re[evenIndex] = (evenRe + oddRe).toFloat()
                        im[evenIndex] = (evenIm + oddIm).toFloat()
                        re[oddIndex] = (evenRe - oddRe).toFloat()
                        im[oddIndex] = (evenIm - oddIm).toFloat()
                        val nextRe = currentRe * wRe - currentIm * wIm
                        currentIm = currentRe * wIm + currentIm * wRe
                        currentRe = nextRe
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }

    companion object {
        private const val TAG = "LosslessValidator"
        private const val STREAMINFO_READ_BYTES = 8 * 1024
        private const val MIN_SAMPLE_RATE = 44_100
        private const val MIN_BITS_PER_SAMPLE = 16
        private const val MAX_BITS_PER_SAMPLE = 32
        private const val MIN_DURATION_SECONDS = 10.0
        private const val FFT_SIZE = 8_192
        private const val ANALYZER_MAX_FRAMES = 40
        private const val MIN_ANALYZED_FRAMES = 4
        private const val DECODE_TIMEOUT_MS = 25_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val SEEK_FRACTION = 0.35
        private const val SEEK_MIN_DURATION_US = 30_000_000L
        /** 1kHz 窗口内超过该跌幅判定为低通悬崖。 */
        private const val TRANSCODE_CLIFF_DB = 50.0
        /** 中频与 15-18kHz 差距过大且存在明显跌幅时，判定为低码率转码。 */
        private const val NARROWBAND_DEFICIT_DB = 26.0
        private const val NARROWBAND_CLIFF_DB = 35.0

        /** 解析 FLAC STREAMINFO；传入缓冲区至少包含前 42 字节。 */
        fun parseStreamInfo(bytes: ByteArray): FlacStreamInfo? {
            if (bytes.size < 42) return null
            if (bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
                bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte()
            ) {
                return null
            }
            var offset = 4
            while (offset + 4 <= bytes.size) {
                val header = bytes[offset].toInt() and 0xFF
                val isLast = header and 0x80 != 0
                val type = header and 0x7F
                val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
                offset += 4
                if (type == 0 && length >= 34 && offset + 34 <= bytes.size) {
                    return readStreamInfoBlock(bytes, offset)
                }
                if (isLast) return null
                offset += length
            }
            return null
        }

        private fun readStreamInfoBlock(bytes: ByteArray, offset: Int): FlacStreamInfo? {
            // STREAMINFO 前 10 字节是块大小/帧大小，与校验无关；从第 10 字节开始读采样信息。
            val base = offset + 10
            if (base + 8 > bytes.size) return null
            val b0 = bytes[base].toInt() and 0xFF
            val b1 = bytes[base + 1].toInt() and 0xFF
            val b2 = bytes[base + 2].toInt() and 0xFF
            val b3 = bytes[base + 3].toInt() and 0xFF
            val b4 = bytes[base + 4].toInt() and 0xFF
            val b5 = bytes[base + 5].toInt() and 0xFF
            val b6 = bytes[base + 6].toInt() and 0xFF
            val b7 = bytes[base + 7].toInt() and 0xFF
            val sampleRate = (b0 shl 12) or (b1 shl 4) or (b2 shr 4)
            val channels = ((b2 shr 1) and 0x07) + 1
            val bitsPerSample = (((b2 and 0x01) shl 4) or (b3 shr 4)) + 1
            val totalSamples = ((b3 and 0x0F).toLong() shl 32) or
                ((b4.toLong()) shl 24) or
                ((b5.toLong()) shl 16) or
                ((b6.toLong()) shl 8) or
                b7.toLong()
            if (sampleRate <= 0) return null
            return FlacStreamInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                totalSamples = totalSamples,
            )
        }
    }
}

private fun MediaFormat.getLongOrDefault(key: String, defaultValue: Long): Long =
    runCatching { if (containsKey(key)) getLong(key) else defaultValue }.getOrDefault(defaultValue)

private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
    runCatching { if (containsKey(key)) getInteger(key) else defaultValue }.getOrDefault(defaultValue)
