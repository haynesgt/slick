package com.haynesgt.slick

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

class GoogleDriveSyncService(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val folderName = "SlickDrawings"
    private var driveService: Drive? = null

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        initializeDriveService()
    }

    private fun initializeDriveService() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val driveScope = Scope(DriveScopes.DRIVE_FILE)
        if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
            Log.d("SlickSync", "Initializing Drive service for account: ${account.email}")
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account
            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("Slick").build()
        } else {
            driveService = null
            if (account == null) {
                Log.w("SlickSync", "No signed in account found")
            } else {
                Log.w("SlickSync", "Account found but missing Drive permissions")
            }
        }
    }

    fun reset() {
        driveService = null
        initializeDriveService()
    }

    fun syncFile(file: File) {
        if (driveService == null) {
            initializeDriveService()
            if (driveService == null) {
                _syncStatus.value = SyncStatus.Error("Not signed in to Google Drive")
                return
            }
        }

        _syncStatus.value = SyncStatus.Syncing
        executor.execute {
            try {
                val folderId = getOrCreateFolderId() ?: throw Exception("Failed to get or create folder")
                val driveFileId = findFileId(file.name, folderId)

                val metadata = com.google.api.services.drive.model.File()
                    .setName(file.name)
                    .setParents(Collections.singletonList(folderId))

                val content = FileContent("image/svg+xml", file)

                if (driveFileId == null) {
                    driveService!!.files().create(metadata, content).execute()
                    Log.d("SlickSync", "Created file on Drive: ${file.name}")
                } else {
                    driveService!!.files().update(driveFileId, null, content).execute()
                    Log.d("SlickSync", "Updated file on Drive: ${file.name}")
                }
                _syncStatus.value = SyncStatus.Success
                resetStatusAfterDelay()
            } catch (e: Exception) {
                Log.e("SlickSync", "Failed to sync ${file.name}", e)
                createBackup(file)
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun downloadMissingFiles(localFolder: File, onComplete: () -> Unit = {}) {
        if (driveService == null) {
            initializeDriveService()
            if (driveService == null) {
                _syncStatus.value = SyncStatus.Error("Not signed in to Google Drive")
                return
            }
        }

        _syncStatus.value = SyncStatus.Syncing
        executor.execute {
            try {
                val folderId = getOrCreateFolderId() ?: return@execute
                val query = "'$folderId' in parents and trashed = false and mimeType = 'image/svg+xml'"
                val result = driveService!!.files().list().setQ(query).execute()
                val driveFiles = result.files ?: emptyList()

                var downloadedCount = 0
                for (driveFile in driveFiles) {
                    val localFile = File(localFolder, driveFile.name)
                    if (!localFile.exists()) {
                        FileOutputStream(localFile).use { outputStream ->
                            driveService!!.files().get(driveFile.id).executeMediaAndDownloadTo(outputStream)
                        }
                        downloadedCount++
                        Log.d("SlickSync", "Downloaded missing file: ${driveFile.name}")
                    }
                }
                
                _syncStatus.value = SyncStatus.Success
                resetStatusAfterDelay()
                Log.d("SlickSync", "Download missing files complete. Downloaded $downloadedCount files.")
                onComplete()
            } catch (e: Exception) {
                Log.e("SlickSync", "Failed to download missing files", e)
                _syncStatus.value = SyncStatus.Error(e.message ?: "Download failed")
            }
        }
    }

    private fun resetStatusAfterDelay() {
        scope.launch {
            delay(3000)
            if (_syncStatus.value is SyncStatus.Success) {
                _syncStatus.value = SyncStatus.Idle
            }
        }
    }

    private fun getOrCreateFolderId(): String? {
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = driveService!!.files().list().setQ(query).execute()
        val files = result.files
        if (files != null && files.isNotEmpty()) {
            return files[0].id
        }

        val metadata = com.google.api.services.drive.model.File()
            .setName(folderName)
            .setMimeType("application/vnd.google-apps.folder")
        
        val folder = driveService!!.files().create(metadata).execute()
        return folder.id
    }

    private fun findFileId(name: String, folderId: String): String? {
        val query = "name = '$name' and '$folderId' in parents and trashed = false"
        val result = driveService!!.files().list().setQ(query).execute()
        val files = result.files
        return if (files != null && files.isNotEmpty()) files[0].id else null
    }

    private fun createBackup(file: File) {
        try {
            val backupFolder = File(context.filesDir, "backups")
            if (!backupFolder.exists()) backupFolder.mkdirs()
            val backupFile = File(backupFolder, "${file.name}.${System.currentTimeMillis()}.bak")
            file.copyTo(backupFile, overwrite = true)
            Log.d("SlickSync", "Created local backup: ${backupFile.name}")
        } catch (e: Exception) {
            Log.e("SlickSync", "Failed to create backup", e)
        }
    }
}
