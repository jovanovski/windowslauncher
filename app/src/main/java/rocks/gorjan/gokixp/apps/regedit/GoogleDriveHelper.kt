package rocks.gorjan.gokixp.apps.regedit

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

class GoogleDriveHelper(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveHelper"
        private const val APP_FOLDER_NAME = "Windows Launcher Settings"
        private const val BACKUP_FILE_NAME = "registry_backup.json"
    }

    private var driveService: Drive? = null

    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            // Check if we have the required scope
            val hasScope = GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
            Log.d(TAG, "Account exists: ${account.email}, has Drive scope: $hasScope")

            // If we have the account but not the scope, request it
            if (!hasScope) {
                Log.d(TAG, "Account signed in but missing Drive scope")
            }

            return hasScope
        }
        Log.d(TAG, "No account signed in")
        return false
    }

    fun getSignInIntent(): Intent {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        val client = GoogleSignIn.getClient(context, signInOptions)
        return client.signInIntent
    }

    fun handleSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            Log.d(TAG, "Initializing Drive service for account: ${account.email}")
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account

            driveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("GokiXP")
                .build()

            Log.d(TAG, "Google Drive service initialized successfully")
        } else {
            Log.w(TAG, "Account is null, cannot initialize Drive service")
        }
    }

    suspend fun exportToGoogleDrive(jsonContent: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting export to Google Drive, content size: ${jsonContent.length}")
            val service = driveService ?: run {
                Log.e(TAG, "Drive service is null")
                return@withContext Result.failure(
                    IllegalStateException("Not signed in to Google Drive")
                )
            }

            Log.d(TAG, "Finding or creating app folder")
            // Find or create app folder
            val folderId = findOrCreateAppFolder(service)
            Log.d(TAG, "App folder ID: $folderId")

            // Check if backup file already exists
            val existingFileId = findFileInFolder(service, folderId, BACKUP_FILE_NAME)
            Log.d(TAG, "Existing file ID: ${existingFileId ?: "none"}")

            val file = File().apply {
                name = BACKUP_FILE_NAME
                mimeType = "application/json"
                if (existingFileId == null) {
                    parents = listOf(folderId)
                }
            }

            val contentStream = java.io.ByteArrayInputStream(jsonContent.toByteArray())
            val mediaContent = com.google.api.client.http.InputStreamContent(
                "application/json",
                contentStream
            )

            Log.d(TAG, "Uploading file to Google Drive...")
            val uploadedFile = if (existingFileId != null) {
                // Update existing file
                Log.d(TAG, "Updating existing file")
                service.files().update(existingFileId, file, mediaContent).execute()
            } else {
                // Create new file
                Log.d(TAG, "Creating new file")
                service.files().create(file, mediaContent)
                    .setFields("id, name")
                    .execute()
            }

            Log.d(TAG, "File uploaded to Google Drive successfully: ${uploadedFile.name}")
            Result.success("Backup saved to Google Drive successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to Google Drive: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun importFromGoogleDrive(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext Result.failure(
                IllegalStateException("Not signed in to Google Drive")
            )

            // Find app folder
            val folderId = findAppFolder(service)
                ?: return@withContext Result.failure(IOException("No backup found on Google Drive"))

            // Find backup file
            val fileId = findFileInFolder(service, folderId, BACKUP_FILE_NAME)
                ?: return@withContext Result.failure(IOException("No backup file found on Google Drive"))

            // Download file content
            val outputStream = ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            val content = outputStream.toString("UTF-8")

            Log.d(TAG, "File downloaded from Google Drive")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from Google Drive", e)
            Result.failure(e)
        }
    }

    private fun findOrCreateAppFolder(service: Drive): String {
        return findAppFolder(service) ?: createAppFolder(service)
    }

    private fun findAppFolder(service: Drive): String? {
        val query = "mimeType='application/vnd.google-apps.folder' and name='$APP_FOLDER_NAME' and trashed=false"
        val result: FileList = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return result.files.firstOrNull()?.id
    }

    private fun createAppFolder(service: Drive): String {
        val folderMetadata = File().apply {
            name = APP_FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }

        val folder = service.files().create(folderMetadata)
            .setFields("id")
            .execute()

        Log.d(TAG, "Created app folder: ${folder.id}")
        return folder.id
    }

    private fun findFileInFolder(service: Drive, folderId: String, fileName: String): String? {
        val query = "'$folderId' in parents and name='$fileName' and trashed=false"
        val result: FileList = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return result.files.firstOrNull()?.id
    }

    fun signOut() {
        val client = GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        )
        client.signOut()
        driveService = null
        Log.d(TAG, "Signed out from Google Drive")
    }
}
