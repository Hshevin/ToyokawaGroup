package com.example.skyedge.core.geo

/**
 * TIFF / GeoTIFF LZW strip decompressor (libtiff-compatible, MSB-first codes).
 */
internal object TiffLzwDecompressor {
    private const val CLEAR = 256
    private const val EOI = 257
    private const val FIRST = 258
    private const val BITS_MIN = 9
    private const val BITS_MAX = 12

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        val earlyChange = !(data.size >= 2 && data[0] == 0.toByte() && (data[1].toInt() and 0x01) != 0)
        val table = Array(4096) { CodeEntry() }
        for (code in 0 until 256) {
            table[code].apply {
                value = code
                firstChar = code
                length = 1
                next = null
            }
        }

        var bytePos = 0
        var nextData = 0L
        var nextBits = 0
        var nBits = BITS_MIN
        var nBitsMask = (1 shl nBits) - 1
        var maxCodeIndex = nBitsMask - if (earlyChange) 1 else 0

        var freeEnt = FIRST
        var oldCode: CodeEntry? = null
        val output = ByteArray(expectedSize)
        var outputPos = 0

        fun readCode(): Int {
            while (nextBits < nBits) {
                if (bytePos >= data.size) return EOI
                nextData = (nextData shl 8) or (data[bytePos++].toInt() and 0xFF).toLong()
                nextBits += 8
            }
            val code = ((nextData shr (nextBits - nBits)) and nBitsMask.toLong()).toInt()
            nextBits -= nBits
            return code
        }

        fun appendCodeBytes(codeEntry: CodeEntry) {
            val stack = IntArray(codeEntry.length)
            var depth = codeEntry.length
            var cursor: CodeEntry? = codeEntry
            while (cursor != null && depth > 0) {
                stack[--depth] = cursor.value
                cursor = cursor.next
            }
            for (idx in stack.indices) {
                if (outputPos >= expectedSize) return
                output[outputPos++] = stack[idx].toByte()
            }
        }

        while (outputPos < expectedSize) {
            var code = readCode()
            if (code == EOI) break
            if (code == CLEAR) {
                freeEnt = FIRST
                nBits = BITS_MIN
                nBitsMask = (1 shl nBits) - 1
                maxCodeIndex = nBitsMask - if (earlyChange) 1 else 0
                oldCode = null
                do {
                    code = readCode()
                } while (code == CLEAR)
                if (code == EOI) break
                require(code <= 255) { "LZW CLEAR 后遇到非法首码: $code" }
                output[outputPos++] = code.toByte()
                oldCode = table[code]
                continue
            }

            if (oldCode != null && freeEnt < table.size) {
                val newEntry = table[freeEnt]
                newEntry.next = oldCode
                newEntry.firstChar = oldCode.firstChar
                newEntry.length = oldCode.length + 1
                newEntry.value = if (code < freeEnt) {
                    table[code].firstChar
                } else {
                    newEntry.firstChar
                }
                freeEnt++
                if (freeEnt - 1 > maxCodeIndex) {
                    nBits++
                    if (nBits > BITS_MAX) nBits = BITS_MAX
                    nBitsMask = (1 shl nBits) - 1
                    maxCodeIndex = nBitsMask - if (earlyChange) 1 else 0
                }
            }

            require(code < freeEnt) { "LZW 遇到非法编码: code=$code freeEnt=$freeEnt" }
            val codeEntry = table[code]
            oldCode = codeEntry

            if (code >= 256) {
                appendCodeBytes(codeEntry)
            } else {
                if (outputPos >= expectedSize) break
                output[outputPos++] = code.toByte()
            }
        }

        require(outputPos == expectedSize) {
            "LZW 解压长度不匹配: got=$outputPos expected=$expectedSize"
        }
        return output
    }

    private class CodeEntry {
        var value: Int = 0
        var firstChar: Int = 0
        var length: Int = 0
        var next: CodeEntry? = null
    }
}
