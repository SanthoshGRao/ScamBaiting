package com.scamshield.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageHelper {
    fun saveBase64ToCache(context: Context, base64Str: String): Uri? {
        return try {
            val decodedString = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            
            val cachePath = File(context.cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = File(cachePath, "generated_${UUID.randomUUID()}.jpg")
            
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            fos.close()
            
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
