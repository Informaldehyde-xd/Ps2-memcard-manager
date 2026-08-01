package com.ps2mc.manager.mc

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone

private const val PSU_PAD = 1024 // PSU pads each file's data to a multiple of 1024 bytes, always

/** A single file inside a parsed .psu save. */
data class PsuFileEntry(val mode: Int, val name: String, val data: ByteArray)

/** A parsed .psu save file: the folder's own metadata plus its files. */
data class PsuSave(val name: String, val mode: Int, val createdEpochMillis: Long, val files: List<PsuFileEntry>)

/**
 * Reads and writes the .psu save format — verified byte-for-byte against mymc's
 * ps2save.py (load_ems / save_ems), not inferred: a folder dirent, then "." and
 * ".." dirents (mode 0x8427, length/cluster 0), then for each file: the file's
 * own dirent followed by its raw data padded to a 1024-byte boundary.
 */
object McPsu {

    /** Parses a .psu file's raw bytes into structured save data. */
    fun parse(bytes: ByteArray): PsuSave {
        if (bytes.size < McDirEntry.ENTRY_SIZE * 3) {
            throw McParseException("File is too small to be a .psu save (${bytes.size} bytes).")
        }
        var pos = 0
        fun readEntry(): McDirEntry {
            val e = McDirEntry.parse(bytes, pos)
            pos += McDirEntry.ENTRY_SIZE
            return e
        }

        val folderEnt = readEntry()
        val dotEnt = readEntry()
        val dotDotEnt = readEntry()
        if (!folderEnt.isDirectory || !dotEnt.isDirectory || !dotDotEnt.isDirectory || folderEnt.length < 2) {
            throw McParseException("Doesn't look like a valid .psu save file (bad header entries).")
        }

        val fileCount = folderEnt.length - 2
        val files = mutableListOf<PsuFileEntry>()
        for (i in 0 until fileCount) {
            if (pos + McDirEntry.ENTRY_SIZE > bytes.size) {
                throw McParseException("Unexpected end of .psu file while reading entry $i of $fileCount.")
            }
            val fEnt = readEntry()
            val flen = fEnt.length
            if (pos + flen > bytes.size) {
                throw McParseException("Unexpected end of .psu file while reading data for \"${fEnt.name}\".")
            }
            val data = bytes.copyOfRange(pos, pos + flen)
            pos += flen
            val padded = ((flen + PSU_PAD - 1) / PSU_PAD) * PSU_PAD
            pos += (padded - flen)
            files.add(PsuFileEntry(fEnt.mode, fEnt.name, data))
        }
        return PsuSave(folderEnt.name, folderEnt.mode, folderEnt.createdEpochMillis, files)
    }

    /** Builds a .psu file's raw bytes from a folder already loaded from a card image. */
    fun build(sourceImage: McCardImage, folder: McDirEntry): ByteArray {
        val out = ByteArrayOutputStream()
        val files = sourceImage.listDirectory(folder.cluster).filter { it.name != "." && it.name != ".." }

        out.write(buildDirEntryBytes(folder.mode, files.size + 2, folder.name, folder.createdEpochMillis))
        out.write(buildDirEntryBytes(0x8427, 0, ".", folder.createdEpochMillis))
        out.write(buildDirEntryBytes(0x8427, 0, "..", folder.createdEpochMillis))

        for (f in files) {
            val data = sourceImage.readFileData(f.cluster, f.length)
            out.write(buildDirEntryBytes(f.mode, f.length, f.name, f.modifiedEpochMillis))
            out.write(data)
            val padded = ((f.length + PSU_PAD - 1) / PSU_PAD) * PSU_PAD
            out.write(ByteArray(padded - f.length))
        }
        return out.toByteArray()
    }

    private fun buildDirEntryBytes(mode: Int, length: Int, name: String, epochMillis: Long): ByteArray {
        val buf = ByteArray(McDirEntry.ENTRY_SIZE)
        buf[0] = (mode and 0xFF).toByte()
        buf[1] = ((mode shr 8) and 0xFF).toByte()
        writeIntLE(buf, 4, length)
        writeTimestamp(buf, 8, epochMillis)
        writeIntLE(buf, 16, 0)
        writeIntLE(buf, 20, 0)
        writeTimestamp(buf, 24, epochMillis)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, buf, 64, minOf(nameBytes.size, 31))
        return buf
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeTimestamp(buf: ByteArray, offset: Int, epochMillis: Long) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        buf[offset] = 0
        buf[offset + 1] = cal.get(Calendar.SECOND).toByte()
        buf[offset + 2] = cal.get(Calendar.MINUTE).toByte()
        buf[offset + 3] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        buf[offset + 4] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        buf[offset + 5] = (cal.get(Calendar.MONTH) + 1).toByte()
        val year = cal.get(Calendar.YEAR)
        buf[offset + 6] = (year and 0xFF).toByte()
        buf[offset + 7] = ((year shr 8) and 0xFF).toByte()
    }
}
