package com.altaf.soundamplifier.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1
) {
    private val recording = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ShortArray>(96)

    private var writerThread: Thread? = null
    private var output: RandomAccessFile? = null
    private var currentFile: File? = null
    private var dataBytes: Long = 0

    @Synchronized
    fun start(file: File): Boolean {
        if (recording.get()) return true

        return try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            writeHeader(raf, 0)

            currentFile = file
            output = raf
            dataBytes = 0
            queue.clear()
            recording.set(true)

            writerThread = Thread {
                writerLoop()
            }.also {
                it.name = "AltafWavWriter"
                it.start()
            }

            true
        } catch (_: Throwable) {
            cleanup()
            false
        }
    }

    fun enqueue(samples: ShortArray, count: Int) {
        if (!recording.get() || count <= 0) return

        val safeCount = count.coerceAtMost(samples.size)
        val copy = samples.copyOf(safeCount)

        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
        }
    }

    @Synchronized
    fun stop(): File? {
        if (!recording.getAndSet(false)) {
            return currentFile
        }

        try {
            writerThread?.join(1500)
        } catch (_: InterruptedException) {
        }

        val file = currentFile

        try {
            output?.seek(0)
            output?.let { writeHeader(it, dataBytes) }
        } catch (_: Throwable) {
        }

        cleanup(keepFile = true)
        return file
    }

    fun isRecording(): Boolean = recording.get()

    private fun writerLoop() {
        val byteBuffer = ByteBuffer
            .allocate(4096)
            .order(ByteOrder.LITTLE_ENDIAN)

        while (recording.get() || queue.isNotEmpty()) {
            val chunk = queue.poll(120, TimeUnit.MILLISECONDS) ?: continue

            var index = 0
            while (index < chunk.size) {
                byteBuffer.clear()
                val shortsToWrite = minOf(
                    chunk.size - index,
                    byteBuffer.capacity() / 2
                )

                for (i in 0 until shortsToWrite) {
                    byteBuffer.putShort(chunk[index + i])
                }

                val bytes = byteBuffer.position()
                output?.write(byteBuffer.array(), 0, bytes)
                dataBytes += bytes
                index += shortsToWrite
            }
        }
    }

    private fun writeHeader(raf: RandomAccessFile, pcmDataBytes: Long) {
        val byteRate = sampleRate * channels * 16 / 8
        val blockAlign = channels * 16 / 8
        val totalDataLength = pcmDataBytes + 36

        val header = ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLength.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataBytes.toInt())

        raf.write(header.array())
    }

    private fun cleanup(keepFile: Boolean = false) {
        recording.set(false)
        queue.clear()

        try {
            output?.close()
        } catch (_: Throwable) {
        }

        output = null
        writerThread = null
        dataBytes = 0

        if (!keepFile) {
            try {
                currentFile?.delete()
            } catch (_: Throwable) {
            }
            currentFile = null
        }
    }
}
