package com.ps2mc.manager.data

import android.content.Context
import android.net.Uri
import com.ps2mc.manager.mc.McCardImage
import com.ps2mc.manager.mc.McParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McCardRepository(private val context: Context) {

    /** Loads a memory card image from a picked file Uri. Returns (image, error). */
    suspend fun openCard(uri: Uri): Pair<McCardImage?, String?> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null to "Could not read the selected file."
            val image = McCardImage.open(bytes)
            image to null
        } catch (e: McParseException) {
            null to e.message
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }
}