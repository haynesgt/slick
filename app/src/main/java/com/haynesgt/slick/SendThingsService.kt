package com.haynesgt.slick

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class SendThingsService {
    fun sendData(file: File, context: Context, activity: MainActivity) {
        val fileUri = FileProvider.getUriForFile(context, "com.haynesgt.slick.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream" // Change to your file type (e.g., "image/png" or "text/plain")
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        activity.startActivity(Intent.createChooser(intent, "Send file using"))
    }
}
