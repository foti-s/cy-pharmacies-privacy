package cy.pharmacies.app.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Just enough of an Excel reader to get the cell values out of the first sheet of
 * the Ministry of Health's schedule files. They publish legacy .xls (BIFF8) for most
 * districts and .xlsx for some, so both are handled. A full library such as Apache POI
 * would add several MB to the app for this one job.
 *
 * Cells come back as String, Double (numbers and dates, which Excel stores as day
 * counts) or null for empty cells.
 */
object Spreadsheet {

    fun readFirstSheet(bytes: ByteArray): List<List<Any?>> = when {
        bytes.startsWith(ZIP_MAGIC) -> Xlsx.read(bytes)
        bytes.startsWith(OLE_MAGIC) -> Xls.read(bytes)
        else -> throw IOException("Not an Excel file")
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    /** Collects cells by position and turns them into rows, filling gaps with nulls. */
    private class Grid {
        private val cells = HashMap<Int, HashMap<Int, Any>>()
        private var maxRow = -1

        fun put(row: Int, col: Int, value: Any?) {
            if (value == null || row < 0 || col < 0) return
            cells.getOrPut(row) { HashMap() }[col] = value
            if (row > maxRow) maxRow = row
        }

        fun rows(): List<List<Any?>> = (0..maxRow).map { r ->
            val row = cells[r] ?: return@map emptyList()
            val width = (row.keys.maxOrNull() ?: -1) + 1
            List(width) { c -> row[c] }
        }
    }

    // ---------------------------------------------------------------- .xlsx

    private object Xlsx {
        fun read(bytes: ByteArray): List<List<Any?>> {
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name.removePrefix("/")] = zip.readBytes()
                }
            }
            val strings = entries["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
            val sheet = entries[firstSheetPath(entries)] ?: throw IOException("Spreadsheet has no sheets")
            return cells(sheet, strings)
        }

        /** Follows workbook.xml → its relationships to the first sheet, falling back to sheet1.xml. */
        private fun firstSheetPath(entries: Map<String, ByteArray>): String {
            val fallback = "xl/worksheets/sheet1.xml"
            var relId: String? = null
            entries["xl/workbook.xml"]?.let { xml ->
                parse(xml) { name, attrs -> if (name == "sheet" && relId == null) relId = attr(attrs, "id") }
            }
            var target: String? = null
            entries["xl/_rels/workbook.xml.rels"]?.let { xml ->
                parse(xml) { name, attrs ->
                    if (name == "Relationship" && attrs.getValue("Id") == relId) target = attrs.getValue("Target")
                }
            }
            val path = target?.let { if (it.startsWith("/")) it.removePrefix("/") else "xl/$it" }
            return if (path != null && path in entries) path else fallback
        }

        private fun sharedStrings(xml: ByteArray): List<String> {
            val result = ArrayList<String>()
            val text = StringBuilder()
            var inText = false
            var inPhonetic = false
            parse(xml, onStart = { name, _ ->
                when (name) {
                    "si" -> text.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inText = !inPhonetic
                }
            }, onText = { if (inText) text.append(it) }, onEnd = { name ->
                when (name) {
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                    "si" -> result += text.toString()
                }
            })
            return result
        }

        private fun cells(xml: ByteArray, strings: List<String>): List<List<Any?>> {
            val grid = Grid()
            var row = -1
            var col = -1
            var type = ""
            val value = StringBuilder()
            var capture = false
            parse(xml, onStart = { name, attrs ->
                when (name) {
                    "row" -> row = (attrs.getValue("r")?.toIntOrNull() ?: (row + 2)) - 1
                    "c" -> {
                        val ref = attrs.getValue("r")
                        col = if (ref != null) columnIndex(ref) else col + 1
                        ref?.let { r -> r.dropWhile { it.isLetter() }.toIntOrNull()?.let { row = it - 1 } }
                        type = attrs.getValue("t") ?: "n"
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }, onText = { if (capture) value.append(it) }, onEnd = { name ->
                when (name) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val cell: Any? = when {
                            raw.isEmpty() -> null
                            type == "s" -> raw.toIntOrNull()?.let { strings.getOrNull(it) }
                            type == "n" -> raw.toDoubleOrNull() ?: raw
                            else -> raw // str, inlineStr, b, e
                        }
                        grid.put(row, col, cell)
                    }
                }
            })
            return grid.rows()
        }

        /** "C12" → 2 */
        private fun columnIndex(ref: String): Int {
            var n = 0
            for (ch in ref) {
                if (!ch.isLetter()) break
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
            }
            return n - 1
        }

        private fun attr(attrs: Attributes, localName: String): String? {
            for (i in 0 until attrs.length) {
                if (attrs.getQName(i).substringAfter(':') == localName) return attrs.getValue(i)
            }
            return null
        }

        private fun parse(xml: ByteArray, onStart: (String, Attributes) -> Unit) =
            parse(xml, onStart, onText = {}, onEnd = {})

        private fun parse(
            xml: ByteArray,
            onStart: (String, Attributes) -> Unit,
            onText: (String) -> Unit,
            onEnd: (String) -> Unit,
        ) {
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) =
                    onStart(qName.substringAfter(':'), attributes)

                override fun endElement(uri: String?, localName: String?, qName: String) =
                    onEnd(qName.substringAfter(':'))

                override fun characters(ch: CharArray, start: Int, length: Int) =
                    onText(String(ch, start, length))
            }
            SAXParserFactory.newInstance().newSAXParser().parse(ByteArrayInputStream(xml), handler)
        }
    }

    // ---------------------------------------------------------------- .xls

    /**
     * Legacy Excel: a BIFF8 "Workbook" stream stored inside an OLE2 compound file.
     * Specs: [MS-CFB] for the container, [MS-XLS] for the records.
     */
    private object Xls {
        fun read(bytes: ByteArray): List<List<Any?>> = readCells(workbookStream(bytes))

        // --- OLE2 compound file: a small FAT file system, we only need one stream from it.

        private const val END_OF_CHAIN = -2
        private const val FREE_SECTOR = -1

        private fun workbookStream(bytes: ByteArray): ByteArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val sectorSize = 1 shl buf.getShort(0x1E).toInt()
            val miniSectorSize = 1 shl buf.getShort(0x20).toInt()
            val firstDirSector = buf.getInt(0x30)
            val miniCutoff = buf.getInt(0x38)
            val firstMiniFatSector = buf.getInt(0x3C)
            var difatSector = buf.getInt(0x44)

            fun sectorOffset(sector: Int) = (sector + 1) * sectorSize

            // The FAT's own sectors are listed in the header, then in a chain of DIFAT sectors.
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) buf.getInt(0x4C + i * 4).let { if (it >= 0) fatSectors += it }
            var guard = 0
            while (difatSector >= 0 && guard++ < 10_000) {
                val base = sectorOffset(difatSector)
                val perSector = sectorSize / 4 - 1
                for (i in 0 until perSector) buf.getInt(base + i * 4).let { if (it >= 0) fatSectors += it }
                difatSector = buf.getInt(base + perSector * 4)
            }
            val fat = IntArray(fatSectors.size * sectorSize / 4)
            fatSectors.forEachIndexed { i, s ->
                val base = sectorOffset(s)
                for (j in 0 until sectorSize / 4) fat[i * sectorSize / 4 + j] = buf.getInt(base + j * 4)
            }

            fun chain(start: Int, table: IntArray): List<Int> {
                val result = ArrayList<Int>()
                var s = start
                while (s >= 0 && s < table.size && result.size <= table.size) {
                    result += s
                    s = table[s]
                }
                if (s != END_OF_CHAIN && s != FREE_SECTOR && result.size <= table.size && s >= 0) {
                    throw IOException("Corrupt spreadsheet")
                }
                return result
            }

            fun readChain(start: Int): ByteArray {
                val sectors = chain(start, fat)
                val out = ByteArray(sectors.size * sectorSize)
                sectors.forEachIndexed { i, s ->
                    val off = sectorOffset(s)
                    val n = minOf(sectorSize, bytes.size - off).coerceAtLeast(0)
                    System.arraycopy(bytes, off, out, i * sectorSize, n)
                }
                return out
            }

            // Directory: 128-byte entries. Entry 0 is the root, whose stream holds the mini sectors.
            val dir = ByteBuffer.wrap(readChain(firstDirSector)).order(ByteOrder.LITTLE_ENDIAN)
            var rootStart = END_OF_CHAIN
            var found: Pair<Int, Long>? = null
            for (e in 0 until dir.capacity() / 128) {
                val base = e * 128
                val nameLen = dir.getShort(base + 0x40).toInt()
                if (nameLen < 2 || nameLen > 64) continue
                val name = String(dir.array(), base, nameLen - 2, Charsets.UTF_16LE)
                val type = dir.get(base + 0x42).toInt()
                val start = dir.getInt(base + 0x74)
                val size = dir.getInt(base + 0x78).toLong() and 0xFFFFFFFFL
                if (type == 5) rootStart = start
                if (type == 2 && (name == "Workbook" || name == "Book") && found == null) found = start to size
            }
            val (start, size) = found ?: throw IOException("Spreadsheet has no workbook")

            if (size >= miniCutoff) return readChain(start).copyOf(size.toInt())

            // Small streams live in 64-byte mini sectors inside the root entry's stream.
            val miniStream = readChain(rootStart)
            val miniFatBytes = ByteBuffer.wrap(readChain(firstMiniFatSector)).order(ByteOrder.LITTLE_ENDIAN)
            val miniFat = IntArray(miniFatBytes.capacity() / 4) { miniFatBytes.getInt(it * 4) }
            val out = ByteArray(chain(start, miniFat).size * miniSectorSize)
            chain(start, miniFat).forEachIndexed { i, s ->
                System.arraycopy(miniStream, s * miniSectorSize, out, i * miniSectorSize, miniSectorSize)
            }
            return out.copyOf(size.toInt())
        }

        // --- BIFF8 records

        private const val BOF = 0x0809
        private const val EOF = 0x000A
        private const val SST = 0x00FC
        private const val CONTINUE = 0x003C
        private const val LABEL_SST = 0x00FD
        private const val LABEL = 0x0204
        private const val NUMBER = 0x0203
        private const val RK = 0x027E
        private const val MUL_RK = 0x00BD
        private const val FORMULA = 0x0006
        private const val STRING = 0x0207
        private const val FILE_PASS = 0x002F
        private const val SUBSTREAM_WORKSHEET = 0x0010

        private fun readCells(stream: ByteArray): List<List<Any?>> {
            val buf = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
            val grid = Grid()
            var strings: List<String> = emptyList()
            var depth = 0
            var sheetDepth = -1 // depth of the first worksheet while we are inside it
            var sheetsSeen = 0
            var pendingFormula: Pair<Int, Int>? = null

            var pos = 0
            while (pos + 4 <= stream.size) {
                val type = buf.getShort(pos).toInt() and 0xFFFF
                val len = buf.getShort(pos + 2).toInt() and 0xFFFF
                val body = pos + 4
                if (body + len > stream.size) break
                pos = body + len

                when (type) {
                    BOF -> {
                        depth++
                        val substream = buf.getShort(body + 2).toInt() and 0xFFFF
                        if (substream == SUBSTREAM_WORKSHEET && sheetsSeen++ == 0) sheetDepth = depth
                    }
                    EOF -> {
                        if (depth == sheetDepth) return grid.rows()
                        depth--
                    }
                    FILE_PASS -> throw IOException("Spreadsheet is password protected")
                    SST -> {
                        // The string table spills over into the CONTINUE records that follow it.
                        val segments = arrayListOf(stream.copyOfRange(body, body + len))
                        while (pos + 4 <= stream.size && (buf.getShort(pos).toInt() and 0xFFFF) == CONTINUE) {
                            val cLen = buf.getShort(pos + 2).toInt() and 0xFFFF
                            segments += stream.copyOfRange(pos + 4, minOf(pos + 4 + cLen, stream.size))
                            pos += 4 + cLen
                        }
                        strings = readSst(segments)
                    }
                }

                if (sheetDepth < 0 || depth != sheetDepth) continue
                val row = if (len >= 4) buf.getShort(body).toInt() and 0xFFFF else -1
                val col = if (len >= 4) buf.getShort(body + 2).toInt() and 0xFFFF else -1
                when (type) {
                    LABEL_SST -> grid.put(row, col, strings.getOrNull(buf.getInt(body + 6)))
                    NUMBER -> grid.put(row, col, buf.getDouble(body + 6))
                    RK -> grid.put(row, col, rkValue(buf.getInt(body + 6)))
                    MUL_RK -> {
                        val count = (len - 6) / 6
                        for (i in 0 until count) grid.put(row, col + i, rkValue(buf.getInt(body + 4 + i * 6 + 2)))
                    }
                    LABEL -> grid.put(row, col, Segments(listOf(stream.copyOfRange(body + 6, body + len))).string())
                    FORMULA -> {
                        val isSpecial = (buf.getShort(body + 12).toInt() and 0xFFFF) == 0xFFFF
                        val kind = stream[body + 6].toInt()
                        when {
                            !isSpecial -> grid.put(row, col, buf.getDouble(body + 6))
                            kind == 0 -> pendingFormula = row to col // text result follows in a STRING record
                            kind == 1 -> grid.put(row, col, if (stream[body + 8].toInt() != 0) "TRUE" else "FALSE")
                        }
                    }
                    STRING -> pendingFormula?.let { (r, c) ->
                        grid.put(r, c, Segments(listOf(stream.copyOfRange(body, body + len))).string())
                        pendingFormula = null
                    }
                }
            }
            return grid.rows()
        }

        /** RK: a compressed number, either a 30-bit integer or the top 30 bits of a double, optionally /100. */
        private fun rkValue(rk: Int): Double {
            var value = if (rk and 0x02 != 0) {
                (rk shr 2).toDouble()
            } else {
                java.lang.Double.longBitsToDouble((rk.toLong() and 0xFFFFFFFCL) shl 32)
            }
            if (rk and 0x01 != 0) value /= 100
            return value
        }

        private fun readSst(segments: List<ByteArray>): List<String> {
            val reader = Segments(segments)
            reader.u32() // total references
            val unique = reader.u32()
            val result = ArrayList<String>(unique.coerceIn(0, 100_000))
            repeat(unique) {
                if (reader.atEnd()) return result
                result += reader.string()
            }
            return result
        }

        /**
         * Reads across a record and its CONTINUE records. When a string's characters cross
         * into the next record, that record starts with a fresh flags byte saying whether the
         * rest is stored as 1 or 2 bytes per character.
         */
        private class Segments(private val segments: List<ByteArray>) {
            private var index = 0
            private var pos = 0

            fun atEnd(): Boolean {
                while (index < segments.size && pos >= segments[index].size) { index++; pos = 0 }
                return index >= segments.size
            }

            fun u8(): Int {
                if (atEnd()) throw IOException("Corrupt spreadsheet strings")
                return segments[index][pos++].toInt() and 0xFF
            }

            fun u16() = u8() or (u8() shl 8)
            fun u32() = u16() or (u16() shl 16)

            fun skip(n: Int) = repeat(n) { u8() }

            fun string(): String {
                val length = u16()
                val flags = u8()
                var wide = flags and 0x01 != 0
                val runs = if (flags and 0x08 != 0) u16() else 0
                val extSize = if (flags and 0x04 != 0) u32() else 0
                val text = StringBuilder(length)
                while (text.length < length) {
                    if (pos >= segments[index].size) {
                        index++; pos = 0
                        if (index >= segments.size) break
                        wide = u8() and 0x01 != 0
                    }
                    text.append(if (wide) (u8() or (u8() shl 8)).toChar() else u8().toChar())
                }
                skip(runs * 4 + extSize)
                return text.toString()
            }
        }
    }
}
