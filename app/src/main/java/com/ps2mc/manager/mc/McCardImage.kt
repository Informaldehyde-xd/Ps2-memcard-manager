package com.ps2mc.manager.mc

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val FAT_TERMINATOR = -1 // 0xFFFFFFFF as signed Int — PS2MC_FAT_CHAIN_END
private const val FAT_CLUSTER_MASK = 0x7FFFFFFF // low 31 bits = next-cluster index

/**
 * Parses a raw PS2 memory card image (.ps2/.bin/.mcd) — the format used by
 * PCSX2, uLaunchELF, mymc, PS2 Save Builder, etc.
 *
 * Structure and field offsets verified against Ross Ridge's (mymc author)
 * published PS2 memory card file system documentation and the ps2dev/mymc
 * source (ps2mc.py), not just recollection.
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

    /** Reads page [index] (data only — spare/ECC bytes, if any, are excluded). */
    fun readPage(index: Int): ByteArray {
        val fileOffset = index.toLong() * pageStride
        if (fileOffset + pageSize > raw.size) {
            throw McParseException("Page $index is out of range for this image.")
        }
        return raw.copyOfRange(fileOffset.toInt(), (fileOffset + pageSize).toInt())
    }

    /** Reads cluster [index] (concatenation of pagesPerCluster pages). */
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

    /**
     * Walks the indirect FAT to resolve the full cluster chain starting at [startCluster]
     * (a cluster number relative to superblock.allocOffset, as stored in directory entries).
     * Returns absolute cluster numbers (already offset by allocOffset) in chain order.
     */
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

    /**
     * Reads FAT entry for relative cluster [relCluster] via the two-level indirect FAT
     * described by superblock.ifcList. Returns the next relative cluster in the chain,
     * or FAT_TERMINATOR (0xFFFFFFFF) if this is the last cluster.
     *
     * Per the official format spec: each 32-bit FAT entry has its top bit set when the
     * cluster is allocated, with the real next-cluster index in the lower 31 bits. A
     * previous version of this function used the raw value directly, which (since the
     * allocated bit is always set on real chain entries) came out as a negative Int and
     * silently broke the chain-walk loop after just one cluster. Fixed by special-casing
     * the exact terminator value and masking off the allocated bit otherwise.
     */
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
        val raw = readUInt32LE(fatData, entryIndexInFatCluster * 4)

        if (raw == FAT_TERMINATOR) return FAT_TERMINATOR
        return raw and FAT_CLUSTER_MASK
    }

    /**
     * Diagnostic helper: shows every intermediate value in the two-level indirect-FAT
     * lookup for [relCluster]. Kept around for troubleshooting subdirectories/edge cases
     * even though the root-cause FAT masking bug is now fixed.
     */
    fun dumpFatDebug(relCluster: Int): String = buildString {
        val perCluster = entriesPerFatCluster
        val fatClusterIndex = relCluster / perCluster
        val entryIndexInFatCluster = relCluster % perCluster
        val ifcIndex = fatClusterIndex / perCluster
        val fatPointerIndexInIfc = fatClusterIndex % perCluster

        appendLine("relCluster=$relCluster  entriesPerFatCluster(=clusterSize/4)=$perCluster")
        appendLine("fatClusterIndex=$fatClusterIndex  entryIndexInFatCluster=$entryIndexInFatCluster")
        appendLine("ifcIndex=$ifcIndex  fatPointerIndexInIfc=$fatPointerIndexInIfc")
        appendLine("superblock.ifcList=${superblock.ifcList}")

        if (ifcIndex >= superblock.ifcList.size) {
            appendLine("!! ifcIndex is out of range of ifcList (size=${superblock.ifcList.size})")
            return@buildString
        }
        val ifcCluster = superblock.ifcList[ifcIndex]
        appendLine("ifcCluster (absolute cluster #) = $ifcCluster")

        val ifcData = readCluster(ifcCluster)
        val ifcFirst8 = (0 until 8).map { readUInt32LE(ifcData, it * 4) }
        appendLine("IFC cluster's first 8 u32 entries: $ifcFirst8")

        val fatClusterAbs = readUInt32LE(ifcData, fatPointerIndexInIfc * 4)
        appendLine("-> fatClusterAbs (read at IFC offset ${fatPointerIndexInIfc * 4}) = $fatClusterAbs")

        if (fatClusterAbs < 0 || fatClusterAbs >= superblock.clustersPerCard) {
            appendLine("!! fatClusterAbs looks invalid for this image (clustersPerCard=${superblock.clustersPerCard})")
            return@buildString
        }

        val fatData = readCluster(fatClusterAbs)
        val fatFirst8 = (0 until 8).map { readUInt32LE(fatData, it * 4) }
        appendLine("FAT cluster's first 8 u32 entries: $fatFirst8")

        val nextRaw = readUInt32LE(fatData, entryIndexInFatCluster * 4)
        val nextResolved = if (nextRaw == FAT_TERMINATOR) FAT_TERMINATOR else nextRaw and FAT_CLUSTER_MASK
        appendLine("-> FAT[$relCluster] raw = $nextRaw (hex: 0x${nextRaw.toUInt().toString(16)}), resolved next cluster = $nextResolved")
    }

    /** Lists entries (files and subdirectories) inside the directory whose data starts at [startCluster]. */
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

    /** Convenience: lists the root directory, skipping "." and "..". */
    fun listRoot(): List<McDirEntry> =
        listDirectory(superblock.rootDirCluster).filter { it.name != "." && it.name != ".." }

    /**
     * Diagnostic helper: returns the raw 96 bytes (mode..name) of directory entry [entryIndex]
     * inside the directory starting at [startCluster], as a hex string.
     */
    fun dumpRawEntryHex(startCluster: Int, entryIndex: Int): String {
        val chain = getClusterChain(startCluster)
        val entriesPerCluster = clusterSize / McDirEntry.ENTRY_SIZE
        val clusterIdx = entryIndex / entriesPerCluster
        val indexInCluster = entryIndex % entriesPerCluster
        if (clusterIdx >= chain.size) return "(out of range — chain has ${chain.size} cluster(s))"
        val data = readCluster(chain[clusterIdx])
        val offset = indexInCluster * McDirEntry.ENTRY_SIZE
        val slice = data.copyOfRange(offset, offset + 96)
        return slice.joinToString(" ") { "%02X".format(it) }
    }
    /** Returns a copy of this image's underlying bytes — used as the starting point for McCardWriter. */
    fun rawBytesCopy(): ByteArray = raw.copyOf()

    /**
     * Returns this image's data with any ECC/spare bytes stripped — a "plain" memory card
     * image (512 bytes/page, no gaps), which PCSX2 and most software tools expect by default.
     * Use this for any file written back out after edits: this app's writer only touches the
     * 512-byte data portion of a page, never recomputes the 16-byte hardware ECC, so an edited
     * page's original ECC bytes go stale. A reader that validates ECC would then reject that
     * page as corrupt — exporting plain avoids the problem entirely rather than risking a
     * hand-rolled, untested ECC implementation.
     */
    fun exportPlainBytes(): ByteArray {
        if (!hasEcc) return raw.copyOf()
        val totalPages = raw.size / pageStride
        val out = ByteArray(totalPages * pageSize)
        for (p in 0 until totalPages) {
            val srcOff = p.toLong() * pageStride
            System.arraycopy(raw, srcOff.toInt(), out, p * pageSize, pageSize)
        }
        return out
    }

    /** Reads the raw bytes of a file entry given its starting cluster and byte length. */
    fun readFileData(startCluster: Int, length: Int): ByteArray {

    /** Reads the raw bytes of a file entry given its starting cluster and byte length. */
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

/**
 * PS2 memory card superblock (page 0 of the image). Offsets verified against the
 * official file-system documentation (Ross Ridge / mymc).
 */
data class McSuperblock(
    val magic: String,
    val version: String,
    val pageSize: Int,
    val pagesPerCluster: Int,
    val pagesPerBlock: Int,
    val clustersPerCard: Int,
    val allocOffset: Int,      // first allocatable cluster
    val allocEnd: Int,         // cluster after the last allocatable one
    val rootDirCluster: Int,   // relative to allocOffset
    val backupBlock1: Int,
    val backupBlock2: Int,
    val ifcList: List<Int>,    // indirect FAT cluster pointers (absolute cluster numbers)
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
 * Layout (verified against the official spec):
 *   0x00 mode(2) 0x02 unused(2) 0x04 length(4) 0x08 created(8) 0x10 cluster(4)
 *   0x14 dirEntry(4) 0x18 modified(8) 0x20 attr(4) 0x24 padding(28) 0x40 name(32)
 *   = 512 bytes total (rest is padding).
 *
 * Mode flags confirmed against the spec:
 *   0x0001 DF_READ, 0x0002 DF_WRITE, 0x0004 DF_EXECUTE, 0x0008 DF_PROTECTED,
 *   0x0010 DF_FILE, 0x0020 DF_DIRECTORY, 0x2000 DF_HIDDEN, 0x8000 DF_EXISTS.
 * DF_EXISTS (0x8000) is the correct "used" flag — this entry is in use; if clear,
 * the file/directory has been deleted.
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
    val isUsed: Boolean get() = (mode and 0x8000) != 0
    val isProtected: Boolean get() = (mode and 0x0008) != 0
    val isHidden: Boolean get() = (mode and 0x2000) != 0

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
            val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()

            return McDirEntry(mode, length, cluster, dirEntryIndex, name, created, modified)
        }

        /** PS2 8-byte timestamp: [unused, sec, min, hour, day, month, year_lo, year_hi]. */
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
