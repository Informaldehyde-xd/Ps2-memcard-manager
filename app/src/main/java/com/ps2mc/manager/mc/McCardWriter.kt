package com.ps2mc.manager.mc

import java.util.Calendar
import java.util.TimeZone

/**
 * Write engine for PS2 memory card images. Operates entirely on an in-memory
 * copy of the card's bytes — never mutates the original array/file. Call
 * [exportBytes] to get the finished image for saving to a NEW file.
 *
 * "." / ".." semantics verified against mymc's real source (create_dir_entry,
 * _check_dir): for every directory except root, "." has length=0, cluster=
 * parent's cluster, dirEntryIndex=slot index of this dir within the parent;
 * ".." has length=0, cluster=0, dirEntryIndex=0. Root's own first entry is
 * the one exception where length is the real child count.
 */
class McCardWriter private constructor(
    private val data: ByteArray,
    private val pageSize: Int,
    private val pageStride: Int,
    private val superblock: McSuperblock
) {
    companion object {
        private const val FAT_TERMINATOR = -1
        private const val FAT_ALLOCATED_BIT = Int.MIN_VALUE // 0x80000000
        private const val FAT_CLUSTER_MASK = 0x7FFFFFFF

        /** Creates a writer from a byte array (typically the bytes an McCardImage was opened from). */
        fun from(bytes: ByteArray, image: McCardImage): McCardWriter =
            McCardWriter(bytes.copyOf(), image.pageSize, image.pageStride, image.superblock)
    }

    private val clusterSize: Int get() = pageSize * superblock.pagesPerCluster
    private val entriesPerFatCluster: Int get() = clusterSize / 4
    private val entriesPerDirCluster: Int get() = clusterSize / McDirEntry.ENTRY_SIZE

    private fun absoluteOffset(clusterAbs: Int, byteInCluster: Int): Int {
        val pageWithinCluster = byteInCluster / pageSize
        val offsetWithinPage = byteInCluster % pageSize
        val pageIndex = clusterAbs * superblock.pagesPerCluster + pageWithinCluster
        return (pageIndex.toLong() * pageStride + offsetWithinPage).toInt()
    }

    private fun readU32(clusterAbs: Int, byteInCluster: Int): Int {
        val o = absoluteOffset(clusterAbs, byteInCluster)
        return (data[o].toInt() and 0xFF) or
            ((data[o + 1].toInt() and 0xFF) shl 8) or
            ((data[o + 2].toInt() and 0xFF) shl 16) or
            ((data[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeU32(clusterAbs: Int, byteInCluster: Int, value: Int) {
        val o = absoluteOffset(clusterAbs, byteInCluster)
        data[o] = (value and 0xFF).toByte()
        data[o + 1] = ((value shr 8) and 0xFF).toByte()
        data[o + 2] = ((value shr 16) and 0xFF).toByte()
        data[o + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun readClusterAbs(clusterAbs: Int): ByteArray {
        val out = ByteArray(clusterSize)
        for (p in 0 until superblock.pagesPerCluster) {
            val off = ((clusterAbs * superblock.pagesPerCluster + p).toLong() * pageStride).toInt()
            System.arraycopy(data, off, out, p * pageSize, pageSize)
        }
        return out
    }

    fun writeClusterAbs(clusterAbs: Int, bytes: ByteArray) {
        require(bytes.size == clusterSize) { "Cluster write must be exactly $clusterSize bytes" }
        for (p in 0 until superblock.pagesPerCluster) {
            val off = ((clusterAbs * superblock.pagesPerCluster + p).toLong() * pageStride).toInt()
            System.arraycopy(bytes, p * pageSize, data, off, pageSize)
        }
    }

    private fun zeroCluster(clusterAbs: Int) = writeClusterAbs(clusterAbs, ByteArray(clusterSize))

    private fun fatEntryLocation(relCluster: Int): Pair<Int, Int> {
        val perCluster = entriesPerFatCluster
        val fatClusterIndex = relCluster / perCluster
        val entryIndexInFatCluster = relCluster % perCluster
        val ifcIndex = fatClusterIndex / perCluster
        val fatPointerIndexInIfc = fatClusterIndex % perCluster
        val ifcCluster = superblock.ifcList[ifcIndex]
        val fatClusterAbs = readU32(ifcCluster, fatPointerIndexInIfc * 4)
        return fatClusterAbs to (entryIndexInFatCluster * 4)
    }

    private fun readFatRaw(relCluster: Int): Int {
        val (fatClusterAbs, byteOff) = fatEntryLocation(relCluster)
        return readU32(fatClusterAbs, byteOff)
    }

    private fun writeFatRaw(relCluster: Int, rawValue: Int) {
        val (fatClusterAbs, byteOff) = fatEntryLocation(relCluster)
        writeU32(fatClusterAbs, byteOff, rawValue)
    }

    private fun isClusterFree(relCluster: Int): Boolean =
        (readFatRaw(relCluster) and FAT_ALLOCATED_BIT) == 0

    private fun markAllocated(relCluster: Int, nextRelClusterOrTerminator: Int) {
        val value = if (nextRelClusterOrTerminator == FAT_TERMINATOR) {
            FAT_TERMINATOR
        } else {
            (nextRelClusterOrTerminator and FAT_CLUSTER_MASK) or FAT_ALLOCATED_BIT
        }
        writeFatRaw(relCluster, value)
    }

    fun allocateChain(count: Int): List<Int> {
        require(count > 0)
        val totalDataClusters = superblock.allocEnd - superblock.allocOffset
        val free = mutableListOf<Int>()
        var i = 0
        while (i < totalDataClusters && free.size < count) {
            if (isClusterFree(i)) free.add(i)
            i++
        }
        if (free.size < count) {
            throw McParseException("Not enough free space (needed $count cluster(s), found ${free.size}).")
        }
        for (idx in free.indices) {
            val next = if (idx == free.lastIndex) FAT_TERMINATOR else free[idx + 1]
            markAllocated(free[idx], next)
        }
        return free
    }

    fun getClusterChain(startRel: Int): List<Int> {
        if (startRel < 0) return emptyList()
        val chain = mutableListOf<Int>()
        var current = startRel
        var guard = 0
        while (current != FAT_TERMINATOR && current >= 0 && guard < superblock.clustersPerCard) {
            chain.add(current + superblock.allocOffset)
            val raw = readFatRaw(current)
            current = if (raw == FAT_TERMINATOR) FAT_TERMINATOR else raw and FAT_CLUSTER_MASK
            guard++
        }
        return chain
    }

    private data class RawDirEntry(
        val mode: Int,
        val length: Int,
        val cluster: Int,
        val dirEntryIndex: Int,
        val name: String,
        val epochMillis: Long
    )

    private fun writeDirEntry(clusterAbs: Int, slotIndex: Int, e: RawDirEntry) {
        val buf = ByteArray(512)
        buf[0] = (e.mode and 0xFF).toByte()
        buf[1] = ((e.mode shr 8) and 0xFF).toByte()
        writeIntTo(buf, 4, e.length)
        writeTimestamp(buf, 8, e.epochMillis)
        writeIntTo(buf, 16, e.cluster)
        writeIntTo(buf, 20, e.dirEntryIndex)
        writeTimestamp(buf, 24, e.epochMillis)
        val nameBytes = e.name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, buf, 64, minOf(nameBytes.size, 31))
        val off = absoluteOffset(clusterAbs, slotIndex * McDirEntry.ENTRY_SIZE)
        System.arraycopy(buf, 0, data, off, 512)
    }

    private fun writeIntTo(buf: ByteArray, offset: Int, value: Int) {
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

    private fun countUsedEntries(dirStartRel: Int): Int {
        val chain = getClusterChain(dirStartRel)
        var count = 0
        for (clusterAbs in chain) {
            for (slot in 0 until entriesPerDirCluster) {
                val modeOff = absoluteOffset(clusterAbs, slot * McDirEntry.ENTRY_SIZE)
                val mode = (data[modeOff].toInt() and 0xFF) or ((data[modeOff + 1].toInt() and 0xFF) shl 8)
                if ((mode and 0x8000) != 0) count++
            }
        }
        return count
    }

    private fun updateDotLength(dirStartRel: Int, newLength: Int) {
        val firstAbs = getClusterChain(dirStartRel).first()
        writeU32(firstAbs, 4, newLength)
    }

    private fun updateEntryLengthInParent(parentStartRel: Int, targetRelCluster: Int, newLength: Int) {
        val chain = getClusterChain(parentStartRel)
        for (clusterAbs in chain) {
            for (slot in 0 until entriesPerDirCluster) {
                val base = slot * McDirEntry.ENTRY_SIZE
                val modeOff = absoluteOffset(clusterAbs, base)
                val mode = (data[modeOff].toInt() and 0xFF) or ((data[modeOff + 1].toInt() and 0xFF) shl 8)
                if ((mode and 0x8000) == 0) continue
                val clusterField = readU32(clusterAbs, base + 16)
                if (clusterField == targetRelCluster) {
                    writeU32(clusterAbs, base + 4, newLength)
                    return
                }
            }
        }
    }

    private fun syncDirEntryCount(dirRel: Int, parentOfDirRel: Int?) {
        val newCount = countUsedEntries(dirRel)
        if (dirRel == superblock.rootDirCluster) {
            updateDotLength(dirRel, newCount)
        } else {
            updateDotLength(dirRel, 0)
            if (parentOfDirRel != null) {
                updateEntryLengthInParent(parentOfDirRel, dirRel, newCount)
            }
        }
    }

    private fun findOrCreateFreeDirSlot(parentStartRel: Int): Pair<Int, Int> {
        val chain = getClusterChain(parentStartRel)
        val perCluster = entriesPerDirCluster
        for (clusterAbs in chain) {
            for (slot in 0 until perCluster) {
                val modeOff = absoluteOffset(clusterAbs, slot * McDirEntry.ENTRY_SIZE)
                val mode = (data[modeOff].toInt() and 0xFF) or ((data[modeOff + 1].toInt() and 0xFF) shl 8)
                if ((mode and 0x8000) == 0) return clusterAbs to slot
            }
        }
        val lastAbs = chain.last()
        val lastRel = lastAbs - superblock.allocOffset
        val newRel = allocateChain(1)[0]
        markAllocated(lastRel, newRel)
        val newAbs = newRel + superblock.allocOffset
        zeroCluster(newAbs)
        return newAbs to 0
    }

    private fun globalSlotIndex(parentStartRel: Int, slotClusterAbs: Int, slotIndexInCluster: Int): Int {
        val chain = getClusterChain(parentStartRel)
        val clusterPos = chain.indexOf(slotClusterAbs)
        return clusterPos * entriesPerDirCluster + slotIndexInCluster
    }

    fun createFolder(parentStartRel: Int, parentOfParentStartRel: Int?, name: String): Int {
        val newRel = allocateChain(1)[0]
        val newAbs = newRel + superblock.allocOffset
        zeroCluster(newAbs)

        val (slotClusterAbs, slotIndex) = findOrCreateFreeDirSlot(parentStartRel)
        val parentEntryIndex = globalSlotIndex(parentStartRel, slotClusterAbs, slotIndex)

        val now = System.currentTimeMillis()
        writeDirEntry(newAbs, 0, RawDirEntry(0x8427, 0, parentStartRel, parentEntryIndex, ".", now))
        writeDirEntry(newAbs, 1, RawDirEntry(0x8427, 0, 0, 0, "..", now))

        writeDirEntry(slotClusterAbs, slotIndex, RawDirEntry(0x8427, 2, newRel, 0, name, now))
        syncDirEntryCount(parentStartRel, parentOfParentStartRel)
        return newRel
    }

    private fun writeFileWithData(destParentStartRel: Int, mode: Int, name: String, fileBytes: ByteArray) {
        val lengthBytes = fileBytes.size

        if (lengthBytes == 0) {
            val (slotClusterAbs, slotIndex) = findOrCreateFreeDirSlot(destParentStartRel)
            writeDirEntry(slotClusterAbs, slotIndex, RawDirEntry(mode, 0, -1, 0, name, System.currentTimeMillis()))
            return
        }

        val clustersNeeded = (lengthBytes + clusterSize - 1) / clusterSize
        val newClusters = allocateChain(clustersNeeded)
        var written = 0
        for (relCluster in newClusters) {
            val absCluster = relCluster + superblock.allocOffset
            val chunk = ByteArray(clusterSize)
            val toCopy = minOf(clusterSize, lengthBytes - written)
            if (toCopy > 0) System.arraycopy(fileBytes, written, chunk, 0, toCopy)
            writeClusterAbs(absCluster, chunk)
            written += toCopy
        }

        val (slotClusterAbs, slotIndex) = findOrCreateFreeDirSlot(destParentStartRel)
        writeDirEntry(
            slotClusterAbs, slotIndex,
            RawDirEntry(mode, lengthBytes, newClusters[0], 0, name, System.currentTimeMillis())
        )
    }

    fun copyFileEntry(
        sourceImage: McCardImage,
        sourceEntry: McDirEntry,
        destParentStartRel: Int,
        parentOfDestParentStartRel: Int?
    ) {
        val fileBytes = if (sourceEntry.length > 0) {
            sourceImage.readFileData(sourceEntry.cluster, sourceEntry.length)
        } else ByteArray(0)
        writeFileWithData(destParentStartRel, sourceEntry.mode, sourceEntry.name, fileBytes)
        syncDirEntryCount(destParentStartRel, parentOfDestParentStartRel)
    }

    fun copyFolderInto(
        sourceImage: McCardImage,
        sourceFolder: McDirEntry,
        destParentStartRel: Int,
        parentOfDestParentStartRel: Int?
    ) {
        val newFolderRel = createFolder(destParentStartRel, parentOfDestParentStartRel, sourceFolder.name)
        val sourceFiles = sourceImage.listDirectory(sourceFolder.cluster).filter { it.name != "." && it.name != ".." }
        for (f in sourceFiles) {
            copyFileEntry(sourceImage, f, newFolderRel, destParentStartRel)
        }
    }

    fun importPsuFolder(psu: PsuSave, destParentStartRel: Int, parentOfDestParentStartRel: Int?): Int {
        val newFolderRel = createFolder(destParentStartRel, parentOfDestParentStartRel, psu.name)
        for (f in psu.files) {
            writeFileWithData(newFolderRel, f.mode, f.name, f.data)
            syncDirEntryCount(newFolderRel, destParentStartRel)
        }
        return newFolderRel
    }

    fun exportBytes(): ByteArray = data.copyOf()
}
