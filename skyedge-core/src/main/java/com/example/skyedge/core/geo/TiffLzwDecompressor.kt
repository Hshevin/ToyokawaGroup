package com.example.skyedge.core.geo

/**
 * TIFF / GeoTIFF LZW strip decompressor (OpenJDK TIFFLZWDecompressor algorithm).
 */
internal object TiffLzwDecompressor {
    private const val CLEAR_CODE = 256
    private const val EOI_CODE = 257
    private const val FIRST_CODE = 258

    private val AND_TABLE = intArrayOf(511, 1023, 2047, 4095)

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        require(data.size >= 2) { "LZW 数据过短" }
        require(!(data[0] == 0.toByte() && data[1] == 1.toByte())) {
            "暂不支持 TIFF 5.0-style LZW（0x00 0x01）"
        }

        val output = ByteArray(expectedSize)
        var srcIndex = 0
        var dstIndex = 0
        var nextData = 0
        var nextBits = 0

        var stringTable = Array(4096) { byteArrayOf() }
        var tableIndex = FIRST_CODE
        var bitsToGet = 9

        fun initializeStringTable() {
            stringTable = Array(4096) { byteArrayOf() }
            for (code in 0 until CLEAR_CODE) {
                stringTable[code] = byteArrayOf(code.toByte())
            }
            tableIndex = FIRST_CODE
            bitsToGet = 9
        }

        fun writeString(string: ByteArray) {
            if (dstIndex >= output.size) return
            val maxIndex = minOf(string.size, output.size - dstIndex)
            string.copyInto(output, dstIndex, 0, maxIndex)
            dstIndex += maxIndex
        }

        fun addStringToTable(oldString: ByteArray, newByte: Byte) {
            val entry = ByteArray(oldString.size + 1)
            oldString.copyInto(entry, 0, 0, oldString.size)
            entry[oldString.size] = newByte
            stringTable[tableIndex++] = entry
            when (tableIndex) {
                511 -> bitsToGet = 10
                1023 -> bitsToGet = 11
                2047 -> bitsToGet = 12
            }
        }

        fun addStringToTable(string: ByteArray) {
            stringTable[tableIndex++] = string
            when (tableIndex) {
                511 -> bitsToGet = 10
                1023 -> bitsToGet = 11
                2047 -> bitsToGet = 12
            }
        }

        fun composeString(oldString: ByteArray, newByte: Byte): ByteArray {
            val entry = ByteArray(oldString.size + 1)
            oldString.copyInto(entry, 0, 0, oldString.size)
            entry[oldString.size] = newByte
            return entry
        }

        fun getNextCode(): Int {
            return try {
                nextData = (nextData shl 8) or (data[srcIndex++].toInt() and 0xFF)
                nextBits += 8
                if (nextBits < bitsToGet) {
                    nextData = (nextData shl 8) or (data[srcIndex++].toInt() and 0xFF)
                    nextBits += 8
                }
                val code = (nextData shr (nextBits - bitsToGet)) and AND_TABLE[bitsToGet - 9]
                nextBits -= bitsToGet
                code
            } catch (_: IndexOutOfBoundsException) {
                EOI_CODE
            }
        }

        initializeStringTable()
        var oldCode = 0
        var code = getNextCode()
        while (code != EOI_CODE) {
            if (code == CLEAR_CODE) {
                initializeStringTable()
                code = getNextCode()
                if (code == EOI_CODE) break
                writeString(stringTable[code])
                oldCode = code
            } else if (code < tableIndex) {
                val string = stringTable[code]
                writeString(string)
                addStringToTable(stringTable[oldCode], string[0])
                oldCode = code
            } else {
                val string = composeString(stringTable[oldCode], stringTable[oldCode][0])
                writeString(string)
                addStringToTable(string)
                oldCode = code
            }
            if (dstIndex >= expectedSize) break
            code = getNextCode()
        }

        require(dstIndex == expectedSize) {
            "LZW 解压长度不匹配: got=$dstIndex expected=$expectedSize"
        }
        return output
    }
}
