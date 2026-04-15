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

    suspend fun syncFile(file: File, folderName: String = this.folderName) = withContext(Dispatchers.IO) {
        if (driveService == null) {
            initializeDriveService()
            if (driveService == null) {
                _syncStatus.value = SyncStatus.Error("Not signed in to Google Drive")
                return@withContext
            }
        }

        _syncStatus.value = SyncStatus.Syncing
        try {
            val folderId = getOrCreateFolderId(folderName) ?: throw Exception("Failed to get or create folder")
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

    suspend fun downloadMissingFiles(localFolder: File) = withContext(Dispatchers.IO) {
        if (driveService == null) {
            initializeDriveService()
            if (driveService == null) {
                _syncStatus.value = SyncStatus.Error("Not signed in to Google Drive")
                return@withContext
            }
        }

        _syncStatus.value = SyncStatus.Syncing
        try {
            val folderId = getOrCreateFolderId(folderName) ?: return@withContext
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
        } catch (e: Exception) {
            Log.e("SlickSync", "Failed to download missing files", e)
            _syncStatus.value = SyncStatus.Error(e.message ?: "Download failed")
        }
    }

    fun listFilesInFolder(folderPath: String): List<com.google.api.services.drive.model.File> {
        if (driveService == null) return emptyList()
        try {
            val folderId = getOrCreateFolderId(folderPath) ?: return emptyList()
            val query = "'$folderId' in parents and trashed = false"
            val result = driveService!!.files().list().setQ(query).execute()
            return result.files ?: emptyList()
        } catch (e: Exception) {
            Log.e("SlickSync", "Failed to list files in $folderPath", e)
            return emptyList()
        }
    }

    fun downloadFile(fileId: String, localFile: File) {
        val drive = driveService ?: throw Exception("Drive service not initialized")
        FileOutputStream(localFile).use { outputStream ->
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
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

    private fun getOrCreateFolderId(path: String): String? {
        val parts = path.split("/")
        var parentId: String? = "root"

        for (part in parts) {
            val query = "name = '$part' and mimeType = 'application/vnd.google-apps.folder' and '$parentId' in parents and trashed = false"
            val result = driveService!!.files().list().setQ(query).execute()
            val files = result.files
            if (files != null && files.isNotEmpty()) {
                parentId = files[0].id
            } else {
                val metadata = com.google.api.services.drive.model.File()
                    .setName(part)
                    .setMimeType("application/vnd.google-apps.folder")
                    .setParents(if (parentId == "root") null else Collections.singletonList(parentId))
                
                val folder = driveService!!.files().create(metadata).execute()
                parentId = folder.id
            }
        }
        return parentId
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
