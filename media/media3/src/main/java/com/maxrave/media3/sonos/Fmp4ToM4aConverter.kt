package com.maxrave.media3.sonos

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.maxrave.logger.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Losslessly converts YouTube's DASH Fragmented MP4 (`fMP4`, e.g. `itag=140` / `141`)
 * into a standard, non-fragmented, faststart Apple `.m4a` (`[ftyp][moov][mdat]`)
 * with complete `stsd`, `stts`, `stsc`, `stsz`, and `stco` sample tables at the start of the file.
 *
 * Sonos speakers require standard sample tables in `moov` to play and seek `.m4a` (`audio/mp4`) streams.
 * Also provides a hardware-decoded `.wav` (`audio/wav`) fallback via Android `MediaCodec` if an upstream
 * stream is ever WebM/Opus instead of MP4.
 */
internal object Fmp4ToM4aConverter {
    private const val TAG = "Fmp4ToM4aConverter"
    private const val SAMPLES_PER_CHUNK = 43 // ~1 second of 44.1kHz AAC frames per MP4 chunk

    private data class Box(
        val type: String,
        val offset: Int,
        val size: Int,
        val headerSize: Int,
    ) {
        val payloadOffset: Int get() = offset + headerSize
        val payloadEnd: Int get() = offset + size
    }

    /**
     * Inspects [rawBytes] and converts a DASH fMP4 into a canonical faststart non-fragmented M4A.
     * Returns `null` if [rawBytes] is not an MP4 container.
     */
    fun convertFmp4ToStandardM4a(rawBytes: ByteArray): ByteArray? {
        try {
            val topBoxes = parseBoxes(rawBytes, 0, rawBytes.size)
            val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return null
            val hasMoof = topBoxes.any { it.type == "moof" }
            if (!hasMoof) {
                // Already a standard non-fragmented MP4
                return rawBytes
            }

            // Extract original metadata from moov
            val moovChildren = parseBoxes(rawBytes, moovBox.payloadOffset, moovBox.payloadEnd)
            val mvhdBox = moovChildren.firstOrNull { it.type == "mvhd" } ?: return null
            val trakBox = moovChildren.firstOrNull { it.type == "trak" } ?: return null

            // Check mvex -> trex for default sample duration & size
            var trexDefaultDuration = 1024
            var trexDefaultSize = 0
            moovChildren.firstOrNull { it.type == "mvex" }?.let { mvex ->
                val mvexChildren = parseBoxes(rawBytes, mvex.payloadOffset, mvex.payloadEnd)
                mvexChildren.firstOrNull { it.type == "trex" }?.let { trex ->
                    if (trex.size >= trex.headerSize + 24) {
                        trexDefaultDuration = readInt32(rawBytes, trex.payloadOffset + 12)
                        trexDefaultSize = readInt32(rawBytes, trex.payloadOffset + 16)
                    }
                }
            }

            val trakChildren = parseBoxes(rawBytes, trakBox.payloadOffset, trakBox.payloadEnd)
            val tkhdBox = trakChildren.firstOrNull { it.type == "tkhd" } ?: return null
            val mdiaBox = trakChildren.firstOrNull { it.type == "mdia" } ?: return null

            val mdiaChildren = parseBoxes(rawBytes, mdiaBox.payloadOffset, mdiaBox.payloadEnd)
            val mdhdBox = mdiaChildren.firstOrNull { it.type == "mdhd" } ?: return null
            val hdlrBox = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: return null
            val minfBox = mdiaChildren.firstOrNull { it.type == "minf" } ?: return null

            val minfChildren = parseBoxes(rawBytes, minfBox.payloadOffset, minfBox.payloadEnd)
            val smhdBox = minfChildren.firstOrNull { it.type == "smhd" }
            val dinfBox = minfChildren.firstOrNull { it.type == "dinf" } ?: return null
            val stblBox = minfChildren.firstOrNull { it.type == "stbl" } ?: return null

            val stblChildren = parseBoxes(rawBytes, stblBox.payloadOffset, stblBox.payloadEnd)
            val stsdBox = stblChildren.firstOrNull { it.type == "stsd" } ?: return null

            // Parse all moof + mdat fragments to collect sample sizes, durations, and raw audio bytes
            val sampleSizes = IntArrayList(8192)
            val sampleDurations = IntArrayList(8192)
            val mdatOut = ByteArrayOutputStream(rawBytes.size)

            var i = 0
            while (i < topBoxes.size) {
                val box = topBoxes[i]
                if (box.type == "moof") {
                    val trafBox =
                        parseBoxes(rawBytes, box.payloadOffset, box.payloadEnd)
                            .firstOrNull { it.type == "traf" }
                    if (trafBox != null) {
                        val trafChildren = parseBoxes(rawBytes, trafBox.payloadOffset, trafBox.payloadEnd)
                        var tfhdDefaultDuration = trexDefaultDuration
                        var tfhdDefaultSize = trexDefaultSize

                        trafChildren.firstOrNull { it.type == "tfhd" }?.let { tfhd ->
                            val flags = readInt32(rawBytes, tfhd.payloadOffset) and 0x00FFFFFF
                            var pos = tfhd.payloadOffset + 8 // version/flags (4) + track_ID (4)
                            if ((flags and 0x000001) != 0) pos += 8 // base_data_offset
                            if ((flags and 0x000002) != 0) pos += 4 // sample_description_index
                            if ((flags and 0x000008) != 0) {
                                tfhdDefaultDuration = readInt32(rawBytes, pos)
                                pos += 4
                            }
                            if ((flags and 0x000010) != 0) {
                                tfhdDefaultSize = readInt32(rawBytes, pos)
                                pos += 4
                            }
                        }

                        var fragmentBytesExpected = 0
                        for (trun in trafChildren.filter { it.type == "trun" }) {
                            val flags = readInt32(rawBytes, trun.payloadOffset) and 0x00FFFFFF
                            val sampleCount = readInt32(rawBytes, trun.payloadOffset + 4)
                            var pos = trun.payloadOffset + 8
                            if ((flags and 0x000001) != 0) pos += 4 // data_offset
                            if ((flags and 0x000004) != 0) pos += 4 // first_sample_flags

                            for (s in 0 until sampleCount) {
                                val dur =
                                    if ((flags and 0x000100) != 0) {
                                        val d = readInt32(rawBytes, pos)
                                        pos += 4
                                        d
                                    } else {
                                        tfhdDefaultDuration
                                    }
                                val sz =
                                    if ((flags and 0x000200) != 0) {
                                        val z = readInt32(rawBytes, pos)
                                        pos += 4
                                        z
                                    } else {
                                        tfhdDefaultSize
                                    }
                                if ((flags and 0x000400) != 0) pos += 4 // sample_flags
                                if ((flags and 0x000800) != 0) pos += 4 // sample_composition_time_offset

                                if (sz > 0) {
                                    sampleSizes.add(sz)
                                    sampleDurations.add(if (dur > 0) dur else 1024)
                                    fragmentBytesExpected += sz
                                }
                            }
                        }

                        // Find the corresponding mdat box immediately following this moof
                        if (i + 1 < topBoxes.size && topBoxes[i + 1].type == "mdat") {
                            val mdat = topBoxes[i + 1]
                            val availableBytes = mdat.payloadEnd - mdat.payloadOffset
                            val copyLen =
                                if (fragmentBytesExpected in 1..availableBytes) {
                                    fragmentBytesExpected
                                } else {
                                    availableBytes
                                }
                            mdatOut.write(rawBytes, mdat.payloadOffset, copyLen)
                            i += 2
                            continue
                        }
                    }
                }
                i++
            }

            val totalSamples = sampleSizes.size
            if (totalSamples == 0) {
                Logger.w(TAG, "No audio samples found in fMP4 fragments")
                return rawBytes
            }

            val mdatPayload = mdatOut.toByteArray()
            var totalMediaDuration = 0L
            for (idx in 0 until totalSamples) {
                totalMediaDuration += sampleDurations.get(idx).toLong()
            }

            // Read timescales from mvhd and mdhd so we can write accurate durations
            val mvhdTimescale = readBoxTimescale(rawBytes, mvhdBox, isMvhdOrMdhd = true)
            val mdhdTimescale = readBoxTimescale(rawBytes, mdhdBox, isMvhdOrMdhd = true)
            val mvhdDuration =
                if (mdhdTimescale > 0 && mvhdTimescale > 0) {
                    (totalMediaDuration * mvhdTimescale) / mdhdTimescale
                } else {
                    totalMediaDuration
                }

            // Build canonical M4A ftyp box
            val ftypBytes = buildM4aFtypBox()

            // Build stts (run-length encoded sample durations)
            val sttsBytes = buildSttsBox(sampleDurations)

            // Build stsc (sample-to-chunk table, grouping SAMPLES_PER_CHUNK frames per chunk)
            val stscBytes = buildStscBox(totalSamples, SAMPLES_PER_CHUNK)

            // Build stsz (sample sizes table)
            val stszBytes = buildStszBox(sampleSizes)

            // Build placeholder stco with 0 offsets first to calculate exact moov size
            val numChunks = (totalSamples + SAMPLES_PER_CHUNK - 1) / SAMPLES_PER_CHUNK
            val stsdBytes = rawBytes.copyOfRange(stsdBox.offset, stsdBox.payloadEnd)
            val placeholderStco = buildStcoBox(IntArray(numChunks))
            val placeholderMoov =
                buildMoovBox(
                    rawBytes = rawBytes,
                    mvhdBox = mvhdBox,
                    tkhdBox = tkhdBox,
                    mdhdBox = mdhdBox,
                    hdlrBox = hdlrBox,
                    smhdBox = smhdBox,
                    dinfBox = dinfBox,
                    stsdBytes = stsdBytes,
                    sttsBytes = sttsBytes,
                    stscBytes = stscBytes,
                    stszBytes = stszBytes,
                    stcoBytes = placeholderStco,
                    mvhdDuration = mvhdDuration,
                    mdhdDuration = totalMediaDuration,
                )

            // Now we know the exact byte offset where mdat payload will begin!
            val mdatBoxStartOffset = ftypBytes.size + placeholderMoov.size
            val mdatPayloadStartOffset = mdatBoxStartOffset + 8

            // Compute exact chunk offsets for stco
            val chunkOffsets = IntArray(numChunks)
            var runningByteOffset = mdatPayloadStartOffset
            var chunkIndex = 0
            for (s in 0 until totalSamples) {
                if (s % SAMPLES_PER_CHUNK == 0) {
                    chunkOffsets[chunkIndex++] = runningByteOffset
                }
                runningByteOffset += sampleSizes.get(s)
            }

            val realStco = buildStcoBox(chunkOffsets)
            val finalMoov =
                buildMoovBox(
                    rawBytes = rawBytes,
                    mvhdBox = mvhdBox,
                    tkhdBox = tkhdBox,
                    mdhdBox = mdhdBox,
                    hdlrBox = hdlrBox,
                    smhdBox = smhdBox,
                    dinfBox = dinfBox,
                    stsdBytes = stsdBytes,
                    sttsBytes = sttsBytes,
                    stscBytes = stscBytes,
                    stszBytes = stszBytes,
                    stcoBytes = realStco,
                    mvhdDuration = mvhdDuration,
                    mdhdDuration = totalMediaDuration,
                )

            val totalFileSize = ftypBytes.size + finalMoov.size + 8 + mdatPayload.size
            val out = ByteBuffer.allocate(totalFileSize).order(ByteOrder.BIG_ENDIAN)
            out.put(ftypBytes)
            out.put(finalMoov)
            out.putInt(8 + mdatPayload.size)
            out.put("mdat".toByteArray(Charsets.US_ASCII))
            out.put(mdatPayload)

            Logger.d(
                TAG,
                "Converted fMP4 (${rawBytes.size}B) -> faststart M4A ($totalFileSize B): $totalSamples samples, $numChunks chunks, duration=${totalMediaDuration / mdhdTimescale.coerceAtLeast(1)}s",
            )
            return out.array()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to convert fMP4 to M4A", e)
            return null
        }
    }

    /**
     * Decodes any Android-supported audio file (e.g., WebM/Opus) into a standard 16-bit PCM WAV file
     * so Sonos hardware can play and seek it natively even if an MP4 stream was unavailable.
     */
    fun decodeToWavFile(
        inputFile: File,
        outputWavFile: File,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return false

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            RandomAccessFile(outputWavFile, "rw").use { raf ->
                raf.setLength(0)
                // Write placeholder 44-byte WAV header
                raf.write(ByteArray(44))

                val bufferInfo = MediaCodec.BufferInfo()
                var isInputEOS = false
                var isOutputEOS = false
                var totalPcmBytes = 0L
                var outSampleRate = sampleRate
                var outChannels = channels

                while (!isOutputEOS) {
                    if (!isInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.get(chunk)
                                raf.write(chunk)
                                totalPcmBytes += bufferInfo.size
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isOutputEOS = true
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                outSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                outChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                    }
                }

                // Write final RIFF/WAV header at byte 0
                raf.seek(0)
                raf.write(buildWavHeader(totalPcmBytes, outSampleRate, outChannels, 16))
            }
            return outputWavFile.length() > 44
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decode audio to WAV", e)
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildWavHeader(
        pcmDataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalDataLen = (pcmDataSize + 36).coerceAtMost(0xFFFFFFFFL).toInt()
        val dataSizeInt = pcmDataSize.coerceAtMost(0xFFFFFFFFL).toInt()
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalDataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // Subchunk1Size for PCM
        buf.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSizeInt)
        return buf.array()
    }

    private fun buildM4aFtypBox(): ByteArray {
        val brands = listOf("M4A ", "isom", "iso2", "mp42")
        val size = 16 + brands.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("ftyp".toByteArray(Charsets.US_ASCII))
        buf.put("M4A ".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // minor_version
        for (brand in brands) {
            buf.put(brand.toByteArray(Charsets.US_ASCII))
        }
        return buf.array()
    }

    private fun buildSttsBox(durations: IntArrayList): ByteArray {
        val entryDurations = IntArrayList(16)
        val entryCounts = IntArrayList(16)
        if (durations.size > 0) {
            var curDur = durations.get(0)
            var curCount = 1
            for (i in 1 until durations.size) {
                val d = durations.get(i)
                if (d == curDur) {
                    curCount++
                } else {
                    entryDurations.add(curDur)
                    entryCounts.add(curCount)
                    curDur = d
                    curCount = 1
                }
            }
            entryDurations.add(curDur)
            entryCounts.add(curCount)
        }
        val numEntries = entryDurations.size
        val size = 16 + numEntries * 8
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("stts".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version + flags
        buf.putInt(numEntries)
        for (i in 0 until numEntries) {
            buf.putInt(entryCounts.get(i))
            buf.putInt(entryDurations.get(i))
        }
        return buf.array()
    }

    private fun buildStscBox(
        totalSamples: Int,
        samplesPerChunk: Int,
    ): ByteArray {
        val fullChunks = totalSamples / samplesPerChunk
        val remainder = totalSamples % samplesPerChunk
        val entries = mutableListOf<Triple<Int, Int, Int>>()
        if (fullChunks > 0) {
            entries.add(Triple(1, samplesPerChunk, 1))
            if (remainder > 0) {
                entries.add(Triple(fullChunks + 1, remainder, 1))
            }
        } else if (remainder > 0) {
            entries.add(Triple(1, remainder, 1))
        }
        val size = 16 + entries.size * 12
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("stsc".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version + flags
        buf.putInt(entries.size)
        for ((firstChunk, spc, sdi) in entries) {
            buf.putInt(firstChunk)
            buf.putInt(spc)
            buf.putInt(sdi)
        }
        return buf.array()
    }

    private fun buildStszBox(sampleSizes: IntArrayList): ByteArray {
        val count = sampleSizes.size
        val size = 20 + count * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("stsz".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version + flags
        buf.putInt(0) // sample_size = 0 (variable)
        buf.putInt(count)
        for (i in 0 until count) {
            buf.putInt(sampleSizes.get(i))
        }
        return buf.array()
    }

    private fun buildStcoBox(chunkOffsets: IntArray): ByteArray {
        val size = 16 + chunkOffsets.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("stco".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version + flags
        buf.putInt(chunkOffsets.size)
        for (offset in chunkOffsets) {
            buf.putInt(offset)
        }
        return buf.array()
    }

    private fun buildMoovBox(
        rawBytes: ByteArray,
        mvhdBox: Box,
        tkhdBox: Box,
        mdhdBox: Box,
        hdlrBox: Box,
        smhdBox: Box?,
        dinfBox: Box,
        stsdBytes: ByteArray,
        sttsBytes: ByteArray,
        stscBytes: ByteArray,
        stszBytes: ByteArray,
        stcoBytes: ByteArray,
        mvhdDuration: Long,
        mdhdDuration: Long,
    ): ByteArray {
        val patchedMvhd = patchBoxDuration(rawBytes, mvhdBox, mvhdDuration, isTkhd = false)
        val patchedTkhd = patchBoxDuration(rawBytes, tkhdBox, mvhdDuration, isTkhd = true)
        val patchedMdhd = patchBoxDuration(rawBytes, mdhdBox, mdhdDuration, isTkhd = false)
        val hdlrBytes = rawBytes.copyOfRange(hdlrBox.offset, hdlrBox.payloadEnd)
        val smhdBytes = smhdBox?.let { rawBytes.copyOfRange(it.offset, it.payloadEnd) } ?: buildDefaultSmhdBox()
        val dinfBytes = rawBytes.copyOfRange(dinfBox.offset, dinfBox.payloadEnd)

        val stblBytes = wrapBox("stbl", stsdBytes, sttsBytes, stscBytes, stszBytes, stcoBytes)
        val minfBytes = wrapBox("minf", smhdBytes, dinfBytes, stblBytes)
        val mdiaBytes = wrapBox("mdia", patchedMdhd, hdlrBytes, minfBytes)
        val trakBytes = wrapBox("trak", patchedTkhd, mdiaBytes)
        return wrapBox("moov", patchedMvhd, trakBytes)
    }

    private fun buildDefaultSmhdBox(): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(16)
        buf.put("smhd".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version + flags
        buf.putShort(0) // balance
        buf.putShort(0) // reserved
        return buf.array()
    }

    private fun wrapBox(
        type: String,
        vararg children: ByteArray,
    ): ByteArray {
        var totalSize = 8
        for (child in children) totalSize += child.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(totalSize)
        buf.put(type.toByteArray(Charsets.US_ASCII))
        for (child in children) buf.put(child)
        return buf.array()
    }

    private fun readBoxTimescale(
        bytes: ByteArray,
        box: Box,
        isMvhdOrMdhd: Boolean,
    ): Int {
        if (!isMvhdOrMdhd) return 44100
        val version = bytes[box.payloadOffset].toInt() and 0xFF
        val timescaleOffset = if (version == 1) box.payloadOffset + 20 else box.payloadOffset + 12
        return if (timescaleOffset + 4 <= box.payloadEnd) {
            readInt32(bytes, timescaleOffset)
        } else {
            44100
        }
    }

    private fun patchBoxDuration(
        bytes: ByteArray,
        box: Box,
        newDuration: Long,
        isTkhd: Boolean,
    ): ByteArray {
        val copy = bytes.copyOfRange(box.offset, box.payloadEnd)
        val payloadStart = box.headerSize
        val version = copy[payloadStart].toInt() and 0xFF
        val buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN)
        if (version == 1) {
            val durOffset = if (isTkhd) payloadStart + 28 else payloadStart + 24
            if (durOffset + 8 <= copy.size) {
                buf.putLong(durOffset, newDuration)
            }
        } else {
            val durOffset = if (isTkhd) payloadStart + 20 else payloadStart + 16
            if (durOffset + 4 <= copy.size) {
                buf.putInt(durOffset, newDuration.coerceAtMost(0x7FFFFFFFL).toInt())
            }
        }
        return copy
    }

    private fun parseBoxes(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): List<Box> {
        val result = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val rawSize = readInt32(bytes, pos).toLong() and 0xFFFFFFFFL
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            val boxSize: Int =
                when (rawSize) {
                    1L -> {
                        if (pos + 16 > end) break
                        headerSize = 16
                        readInt64(bytes, pos + 8).toInt()
                    }
                    0L -> end - pos
                    else -> rawSize.toInt()
                }
            if (boxSize < headerSize || pos + boxSize > end) break
            result.add(Box(type, pos, boxSize, headerSize))
            pos += boxSize
        }
        return result
    }

    private fun readInt32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt64(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((readInt32(bytes, offset).toLong() and 0xFFFFFFFFL) shl 32) or
            (readInt32(bytes, offset + 4).toLong() and 0xFFFFFFFFL)

    private class IntArrayList(
        initialCapacity: Int,
    ) {
        private var data = IntArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(element: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }

        fun get(index: Int): Int = data[index]
    }
}
