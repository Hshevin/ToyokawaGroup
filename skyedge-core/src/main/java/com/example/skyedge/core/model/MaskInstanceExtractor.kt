package com.example.skyedge.core.model

import android.graphics.BitmapFactory
import com.example.skyedge.core.api.BoundingBoxDto

object MaskInstanceExtractor {
    data class Instance(
        val bbox: BoundingBoxDto,
        val pixelArea: Int,
    )

    fun extract(maskPath: String, minAreaPixels: Int = 16): List<Instance> {
        val bitmap = BitmapFactory.decodeFile(maskPath) ?: return emptyList()
        return try {
            extract(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }, minAreaPixels)
        } finally {
            bitmap.recycle()
        }
    }

    fun extract(width: Int, height: Int, pixels: IntArray, minAreaPixels: Int = 16): List<Instance> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val instances = mutableListOf<Instance>()

        for (start in pixels.indices) {
            if (visited[start] || !isForeground(pixels[start])) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            var area = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                enqueueIfForeground(x - 1, y, width, height, pixels, visited, queue, tail).also { tail = it }
                enqueueIfForeground(x + 1, y, width, height, pixels, visited, queue, tail).also { tail = it }
                enqueueIfForeground(x, y - 1, width, height, pixels, visited, queue, tail).also { tail = it }
                enqueueIfForeground(x, y + 1, width, height, pixels, visited, queue, tail).also { tail = it }
            }

            if (area >= minAreaPixels) {
                instances += Instance(
                    bbox = BoundingBoxDto(
                        x = minX.toFloat() / width,
                        y = minY.toFloat() / height,
                        width = (maxX - minX + 1).toFloat() / width,
                        height = (maxY - minY + 1).toFloat() / height,
                    ),
                    pixelArea = area,
                )
            }
        }
        return instances
    }

    private fun enqueueIfForeground(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (x !in 0 until width || y !in 0 until height) return tail
        val idx = y * width + x
        if (visited[idx] || !isForeground(pixels[idx])) return tail
        visited[idx] = true
        queue[tail] = idx
        return tail + 1
    }

    private fun isForeground(pixel: Int): Boolean =
        (pixel and 0xFF) > 0 || ((pixel shr 8) and 0xFF) > 0 || ((pixel shr 16) and 0xFF) > 0
}
