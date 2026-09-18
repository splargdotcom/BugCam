package com.splarg.bugcam

import java.nio.ByteBuffer

/** Pure Kotlin for unit-testing padding, shared chroma planes and rotation. */
object YuvPacking {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun toNv21(
        planes: List<Plane>, left: Int, top: Int, width: Int, height: Int,
        output: ByteArray,
    ) {
        require(planes.size == 3 && width % 2 == 0 && height % 2 == 0)
        require(left % 2 == 0 && top % 2 == 0 && output.size >= width * height * 3 / 2)
        for (planeIndex in 0..2) {
            val plane = planes[planeIndex]
            val shift = if (planeIndex == 0) 0 else 1
            val cols = width shr shift
            val rows = height shr shift
            val origin = plane.buffer.position() + (top shr shift) * plane.rowStride +
                (left shr shift) * plane.pixelStride
            for (row in 0 until rows) {
                val source = origin + row * plane.rowStride
                if (planeIndex == 0 && plane.pixelStride == 1) {
                    val copy = plane.buffer.duplicate()
                    copy.position(source)
                    copy.get(output, row * width, cols)
                } else {
                    for (col in 0 until cols) {
                        val destination = if (planeIndex == 0) row * width + col else
                            width * height + row * width + col * 2 + if (planeIndex == 2) 0 else 1
                        output[destination] = plane.buffer.get(source + col * plane.pixelStride)
                    }
                }
            }
        }
    }

    /** Clockwise rotation. Rotate VU pairs as chroma samples, never individual bytes. */
    fun rotateNv21(input: ByteArray, width: Int, height: Int, degrees: Int, output: ByteArray) {
        require(degrees in listOf(0, 90, 180, 270))
        require(width % 2 == 0 && height % 2 == 0)
        require(input !== output && output.size >= width * height * 3 / 2)
        if (degrees == 0) {
            input.copyInto(output, endIndex = width * height * 3 / 2)
            return
        }
        rotatePlane(input, output, 0, width, height, 1, degrees)
        rotatePlane(input, output, width * height, width / 2, height / 2, 2, degrees)
    }

    private fun rotatePlane(
        source: ByteArray, target: ByteArray, offset: Int, w: Int, h: Int,
        sampleBytes: Int, degrees: Int,
    ) {
        val outWidth = if (degrees == 180) w else h
        for (y in 0 until h) for (x in 0 until w) {
            val dx = when (degrees) { 90 -> h - 1 - y; 180 -> w - 1 - x; else -> y }
            val dy = when (degrees) { 90 -> x; 180 -> h - 1 - y; else -> w - 1 - x }
            val src = offset + (y * w + x) * sampleBytes
            val dst = offset + (dy * outWidth + dx) * sampleBytes
            for (b in 0 until sampleBytes) target[dst + b] = source[src + b]
        }
    }
}
