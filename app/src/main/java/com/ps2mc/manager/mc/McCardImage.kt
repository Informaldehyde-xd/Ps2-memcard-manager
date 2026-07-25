package com.ps2mc.manager.mc

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val FAT_TERMINATOR = -1 // 0xFFFFFFFF as signed Int

/**
 * Parses a raw PS2 memory card image (.ps2/.bin/.mcd) — the format used by
 * PCSX2, uLaunchELF, mymc, PS2 Save Builder, etc.
 *
 * A PS2 memory card is its own tiny filesystem: a superblock, an indirect
 * FAT (two levels of cluster pointers), and 512-byte directory-entry
 * records. This class holds the raw bytes and exposes page/cluster access
 * plus superblock/FAT/directory parsing on top of it.
 *
 * Supports both plain images (512 bytes/page, e.g. PCSX2's default saves)
 * and raw ECC-dump images (512 bytes data + 16 bytes spare = 528/page,
 * e.g. dumps made with MechaPwn/uLaunchELF "raw" mode).
 */
class McCardImage private constructor(
    private val raw: ByteArray,
    val pageSize: Int,       // usually 512
    val pageStride: Int,     // 512 (plain) or 528 (with ECC spare)
    val hasEcc: Boolean
) {
    lateinit var superblock: McSuperblock
        private set

    companion object {
        private const val PLAIN_STRIDE = 512
        private const val ECC_STRIDE = 528 // 512 data + 16 spare

        /** Loads and parses a card image from raw bytes. Throws McParseException on failure. */
        fun open(bytes: ByteArray): McCardImage {
            if (bytes.size < ECC_STRIDE) {
                throw McParseException("File is too small to be a memory card image (${bytes.size} bytes).")
            }

            val magic = "Sony PS2 Memory Card Format "
            val magicCheck = String(bytes, 0, minOf(magic.length, bytes.size), Charsets.US_ASCII)
            if (!magicCheck.startsWith(magic)) {
                throw McParseException(
                    "Doesn't look like a PS2 memory card image — expected magic string " +
                        "\"Sony PS2 Memory Card Format\" at the start of the file, found: " +
                        "\"${magicCheck.take(29)}\""
                )
            }

            val page0 = bytes.copyOfRange(0, PLAIN_STRIDE)
            val probeSuperblock = McSuperblock.parse(page0)
            val expectedPages: Long = probeSuperblock.clustersPerCard.toLong() * probeSuperblock.pagesPerCluster.toLong()
            val fileSize: Long = bytes.size.toLong()

            val stride = when {
                fileSize % ECC_STRIDE == 0L && fileSize / ECC_STRIDE == expectedPages -> ECC_STRIDE
                fileSize % PLAIN_STRIDE == 0L && fileSize / PLAIN_STRIDE == expectedPages -> PLAIN_STRIDE
                fileSize % ECC_STRIDE == 0L -> ECC_STRIDE
                else -> PLAIN_STRIDE
            }
            val hasEcc = stride == ECC_STRIDE

            val image = McCardImage(bytes, PLAIN_STRIDE, stride, hasEcc)
            image.superblock = probeSuperblock
            return image
        }
    }

    fun readPage(index: Int): ByteArray {
        val fileOffset = index.toLong() * pageStride
        if (fileOffset + pageSize > raw.size) {
            throw McParseException("Page $index is out of range for this image.")
        }
        return raw.copyOfRange(fileOffset.toInt(), (fileOffset + pageSize).toInt())
    }

    fun readCluster(index: Int): ByteArray {
        val pagesPerCluster = superblock.pagesPerCluster
        val out = ByteArray(pageSize * pagesPerCluster)
        for (p in 0 until pagesPerCluster) {
            val page = readPage(index * pagesPerCluster + p)
            page.copyInto(out, p * pageSize)
        }
        return out
    }

    val clusterSize: Int get() = pageSize * superblock.pagesPerCluster

    fun getClusterChain(startCluster: Int): List<Int> {
        if (startCluster < 0) return emptyList()
        val chain = mutableListOf<Int>()
        var current = startCluster
        var guard = 0
        val maxClusters = superblock.clustersPerCard
        while (current != FAT_TERMINATOR && current >= 0 && guard < maxClusters) {
            val absolute = current + superblock.allocOffset
            chain.add(absolute)
            current = readFatEntry(current)
            guard++
        }
        if (guard >= maxClusters) {
            throw McParseException("FAT chain from cluster $startCluster looks circular/corrupt — aborting.")
        }
        return chain
    }

    private val entriesPerFatCluster: Int get() = clusterSize / 4

    private fun readFatEntry(relCluster: Int): Int {
        val perCluster = entriesPerFatCluster
        val fatClusterIndex = relCluster / perCluster
        val entryIndexInFatCluster = relCluster % perCluster

        val ifcIndex = fatClusterIndex / perCluster
        val fatPointerIndexInIfc = fatClusterIndex % perCluster

        if (ifcIndex >= superblock.ifcList.size) {
            throw McParseException("FAT lookup for cluster $relCluster is out of range of the indirect FAT list.")
        }
        val ifcCluster = superblock.ifcList[ifcIndex]
        if (ifcCluster < 0) {
            throw McParseException("Indirect FAT cluster entry $ifcIndex is unset — image may be corrupt.")
        }

        val ifcData = readCluster(ifcCluster)
        val fatClusterAbs = readUInt32LE(ifcData, fatPointerIndexInIfc * 4)

        val fatData = readCluster(fatClusterAbs)
        return readUInt32LE(fatData, entryIndexInFatCluster * 4)
    }

    fun listDirectory(startCluster: Int): List<McDirEntry> {
        val chain = getClusterChain(startCluster)
        val entriesPerCluster = clusterSize / McDirEntry.ENTRY_SIZE
        val result = mutableListOf<McDirEntry>()
        for (cluster in chain) {
            val data = readCluster(cluster)
            for (i in 0 until entriesPerCluster) {
                val offset = i * McDirEntry.ENTRY_SIZE
                if (offset + McDirEntry.ENTRY_SIZE > data.size) break
                val entry = McDirEntry.parse(data, offset)
                if (entry.isUsed) result.add(entry)
            }
        }
        return result
    }

    fun listRoot(): List<McDirEntry> =
        listDirectory(superblock.rootDirCluster).filter { it.name != "." && it.name != ".." }

    fun readFileData(startCluster: Int, length: Int): ByteArray {
        val chain = getClusterChain(startCluster)
        val out = ByteArray(length)
        var written = 0
        for (cluster in chain) {
            if (written >= length) break
            val data = readCluster(cluster)
            val toCopy = minOf(data.size, length - written)
            data.copyInto(out, written, 0, toCopy)
            written += toCopy
        }
        return out
    }
}

internal fun readUInt32LE(data: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

internal fun readUInt16LE(data: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

class McParseException(message: String) : Exception(message)

data class McSuperblock(
    val magic: String,
    val version: String,
    val pageSize: Int,
    val pagesPerCluster: Int,
    val pagesPerBlock: Int,
    val clustersPerCard: Int,
    val allocOffset: Int,
    val allocEnd: Int,
    val rootDirCluster: Int,
    val backupBlock1: Int,
    val backupBlock2: Int,
    val ifcList: List<Int>,
    val cardType: Int,
    val cardFlags: Int
) {
    companion object {
        fun parse(page0: ByteArray): McSuperblock {
            val magic = String(page0, 0, 28, Charsets.US_ASCII).trimEnd('\u0000')
            val version = String(page0, 0x1C, 12, Charsets.US_ASCII).trimEnd('\u0000')
            val pageSize = readUInt16LE(page0, 0x28)
            val pagesPerCluster = readUInt16LE(page0, 0x2A)
            val pagesPerBlock = readUInt16LE(page0, 0x2C)
            val clustersPerCard = readUInt32LE(page0, 0x30)
            val allocOffset = readUInt32LE(page0, 0x34)
            val allocEnd = readUInt32LE(page0, 0x38)
            val rootDirCluster = readUInt32LE(page0, 0x3C)
            val backupBlock1 = readUInt32LE(page0, 0x40)
            val backupBlock2 = readUInt32LE(page0, 0x44)

            val ifcList = (0 until 32).map { i -> readUInt32LE(page0, 0x50 + i * 4) }
                .filter { it != -1 }

            val cardType = page0[0x150].toInt() and 0xFF
            val cardFlags = page0[0x151].toInt() and 0xFF

            if (pageSize <= 0 || pagesPerCluster <= 0 || clustersPerCard <= 0) {
                throw McParseException(
                    "Superblock parsed but values look wrong (pageSize=$pageSize, " +
                        "pagesPerCluster=$pagesPerCluster, clustersPerCard=$clustersPerCard) " +
                        "— the byte offsets may not match this image's actual layout."
                )
            }

            return McSuperblock(
                magic, version, pageSize, pagesPerCluster, pagesPerBlock, clustersPerCard,
                allocOffset, allocEnd, rootDirCluster, backupBlock1, backupBlock2,
                ifcList, cardType, cardFlags
            )
        }
    }
}

/**
 * A single 512-byte directory-entry record — one per file or save-folder on the card.
 *
 * Mode bit layout (matches mymc / ps2 save tools convention):
 *   0x0001 DF_READ, 0x0002 DF_WRITE, 0x0004 DF_EXECUTE, 0x0008 DF_PROTECTED,
 *   0x0010 DF_FILE, 0x0020 DF_DIRECTORY, 0x8000 DF_EXISTS (slot is in use).
 * DF_EXISTS is the correct "used" check — a previous version of this file
 * incorrectly used DF_READ (0x0001) for that, which could under- or over-count
 * entries depending on what garbage bits are left in unused slots.
 */
data class McDirEntry(
    val mode: Int,
    val length: Int,      // file: byte size. directory: number of entries it contains.
    val cluster: Int,      // starting cluster (relative to allocOffset)
    val dirEntryIndex: Int, // parent's ordinal; used to reconstruct paths
    val name: String,
    val createdEpochMillis: Long,
    val modifiedEpochMillis: Long
) {
    val isDirectory: Boolean get() = (mode and 0x0020) != 0
    val isUsed: Boolean get() = (mode and 0x8000) != 0 && name.isNotBlank()
    val isProtected: Boolean get() = (mode and 0x0008) != 0

    companion object {
        const val ENTRY_SIZE = 512

        fun parse(data: ByteArray, offset: Int): McDirEntry {
            val mode = readUInt16LE(data, offset + 0)
            val length = readUInt32LE(data, offset + 4)
            val created = parseTimestamp(data, offset + 8)
            val cluster = readUInt32LE(data, offset + 16)
            val dirEntryIndex = readUInt32LE(data, offset + 20)
            val modified = parseTimestamp(data, offset + 24)
            val nameBytes = data.copyOfRange(offset + 64, offset + 64 + 32)
            val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000')

            return McDirEntry(mode, length, cluster, dirEntryIndex, name, created, modified)
        }

        private fun parseTimestamp(data: ByteArray, offset: Int): Long {
            val sec = data[offset + 1].toInt() and 0xFF
            val min = data[offset + 2].toInt() and 0xFF
            val hour = data[offset + 3].toInt() and 0xFF
            val day = data[offset + 4].toInt() and 0xFF
            val month = data[offset + 5].toInt() and 0xFF
            val year = readUInt16LE(data, offset + 6)
            if (year < 1970 || month !in 1..12 || day !in 1..31) return 0L
            return try {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.set(year, month - 1, day, hour, min, sec)
                cal.timeInMillis
            } catch (e: Exception) {
                0L
            }
        }
    }
}
