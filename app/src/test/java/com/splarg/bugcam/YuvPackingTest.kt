package com.splarg.bugcam

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class YuvPackingTest {
    @Test fun paddedPlanarCropDoesNotLeakPaddingAndEmitsVU() {
        val y = ByteBuffer.wrap(byteArrayOf(99,99,99,99,99,99, 99,99,99,99,99,99,
            99,99,1,2,3,4, 99,99,5,6,7,8))
        val u = ByteBuffer.wrap(byteArrayOf(99,99,99, 99,11,12))
        val v = ByteBuffer.wrap(byteArrayOf(99,99,99, 99,21,22))
        val result = ByteArray(12)
        YuvPacking.toNv21(listOf(YuvPacking.Plane(y,6,1),YuvPacking.Plane(u,3,1),
            YuvPacking.Plane(v,3,1)),2,2,4,2,result)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,21,11,22,12),result)
    }

    @Test fun interleavedChromaWithPositionsAndMissingLastPaddingByte() {
        val y = ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8))
        val uv = byteArrayOf(21,11,22,12)
        val v = ByteBuffer.wrap(uv).apply { limit(3) }
        val u = ByteBuffer.wrap(uv).apply { position(1) }
        val result = ByteArray(12)
        YuvPacking.toNv21(listOf(YuvPacking.Plane(y,4,1),YuvPacking.Plane(u,4,2),
            YuvPacking.Plane(v,4,2)),0,0,4,2,result)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,21,11,22,12),result)
    }

    @Test fun rotationsKeepChromaPairsTogether() {
        val input = byteArrayOf(1,2,3,4,5,6,7,8,21,11,22,12)
        val output = ByteArray(12)
        YuvPacking.rotateNv21(input,4,2,90,output)
        assertArrayEquals(byteArrayOf(5,1,6,2,7,3,8,4,21,11,22,12),output)
        val restored = ByteArray(12)
        YuvPacking.rotateNv21(output,2,4,270,restored)
        assertArrayEquals(input,restored)
        YuvPacking.rotateNv21(input,4,2,180,output)
        assertArrayEquals(byteArrayOf(8,7,6,5,4,3,2,1,22,12,21,11),output)
    }
}
