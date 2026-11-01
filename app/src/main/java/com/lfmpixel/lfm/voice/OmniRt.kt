package com.lfmpixel.lfm.voice

import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * LiteRT graphs for OmniVoice through app/src/main/cpp/omni_rt.cpp (LiteRT's C API in libLiteRt.so, from
 * com.google.ai.edge.litert:litert): a graph is opened from a byte range of a file (mapped, never copied) and run on
 * the CPU with buffers that are direct memory shared with the runtime. Not thread-safe: OmniVoice calls it from one
 * thread.
 */
internal object OmniRt {
    init {
        System.loadLibrary("LiteRt")
        System.loadLibrary("omnirt")
    }

    @JvmStatic external fun nativeEnv(): Long
    @JvmStatic external fun nativeFreeEnv(env: Long)
    @JvmStatic external fun nativeOpen(env: Long, fd: Int, offset: Long, size: Long, threads: Int, cache: String?): Long
    @JvmStatic external fun nativeClose(model: Long)
    @JvmStatic external fun nativeSignatures(model: Long): Array<String>
    @JvmStatic external fun nativeNames(model: Long, sig: Int, output: Boolean): Array<String>
    @JvmStatic external fun nativeTensor(model: Long, sig: Int, index: Int, output: Boolean): IntArray
    @JvmStatic external fun nativeBuffer(model: Long, sig: Int, index: Int, output: Boolean): Long
    @JvmStatic external fun nativeBufferData(buffer: Long): ByteBuffer
    @JvmStatic external fun nativeFreeBuffer(buffer: Long)
    @JvmStatic external fun nativeRun(model: Long, sig: Int, inputs: LongArray, outputs: LongArray)
}

/** One LiteRT environment for every graph of a session. */
internal class RtEnv : AutoCloseable {
    val handle = OmniRt.nativeEnv()
    override fun close() = OmniRt.nativeFreeEnv(handle)
}

/** A tensor's memory, shared with the runtime: write before a run, read after it. */
internal class RtBuffer(val handle: Long, val shape: IntArray) : AutoCloseable {
    private val bytes: ByteBuffer = OmniRt.nativeBufferData(handle).order(ByteOrder.nativeOrder())
    val floats: FloatBuffer = bytes.asFloatBuffer()
    val ints: IntBuffer = bytes.asIntBuffer()
    val size: Int get() = shape.fold(1) { a, b -> a * b }

    fun put(v: FloatArray, from: Int = 0, count: Int = v.size, at: Int = 0) { floats.position(at); floats.put(v, from, count) }
    fun putInts(v: IntArray, at: Int = 0) { ints.position(at); ints.put(v) }
    fun get(out: FloatArray = FloatArray(size), at: Int = 0): FloatArray { floats.position(at); floats.get(out, 0, out.size); return out }
    fun getInts(out: IntArray = IntArray(size)): IntArray { ints.position(0); ints.get(out); return out }
    fun byte(i: Int): Int = bytes.get(i).toInt() and 0xff
    fun zero() { for (i in 0 until bytes.capacity()) bytes.put(i, 0) }

    override fun close() = OmniRt.nativeFreeBuffer(handle)
}

/**
 * One graph: [file]'s bytes [offset] until [offset] + [size] (a section of the .litertlm, or a whole .tflite), compiled
 * for the CPU with [threads] threads; [cache] = XNNPACK's weight cache file (the packed weights are mapped from it, once
 * for every signature; written on the first open).
 */
internal class RtGraph(env: RtEnv, file: File, offset: Long, size: Long, threads: Int, cache: File?) : AutoCloseable {
    val handle: Long = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        // LiteRT keeps its own copy of the descriptor.
        OmniRt.nativeOpen(env.handle, pfd.fd, offset, size, threads, cache?.absolutePath)
    }
    val signatures: List<String> = OmniRt.nativeSignatures(handle).toList()

    /** A signature's inputs and outputs by name, and the buffers each run passes (set them with [bind]). */
    inner class Sig(val name: String) {
        val index = signatures.indexOf(name).also { require(it >= 0) { "no signature $name in ${signatures}" } }
        val inputs: List<String> = OmniRt.nativeNames(handle, index, false).toList()
        val outputs: List<String> = OmniRt.nativeNames(handle, index, true).toList()
        private val inB = LongArray(inputs.size)
        private val outB = LongArray(outputs.size)

        fun shape(name: String, output: Boolean = false): IntArray {
            val i = (if (output) outputs else inputs).indexOf(name).also { require(it >= 0) { "$name not in ${this.name}" } }
            return OmniRt.nativeTensor(handle, index, i, output).let { it.copyOfRange(1, it.size) }
        }

        /** A new buffer for an input (or an output) of this signature. */
        fun buffer(name: String, output: Boolean = false): RtBuffer {
            val i = (if (output) outputs else inputs).indexOf(name).also { require(it >= 0) { "$name not in ${this.name}" } }
            return RtBuffer(OmniRt.nativeBuffer(handle, index, i, output), shape(name, output))
        }

        fun bind(name: String, b: RtBuffer, output: Boolean = false) {
            val i = (if (output) outputs else inputs).indexOf(name).also { require(it >= 0) { "$name not in ${this.name}" } }
            if (output) outB[i] = b.handle else inB[i] = b.handle
        }

        fun run() {
            check(inB.all { it != 0L } && outB.all { it != 0L }) { "$name: a buffer is not bound" }
            OmniRt.nativeRun(handle, index, inB, outB)
        }
    }

    override fun close() = OmniRt.nativeClose(handle)
}
