package org.mariotaku.twidere.util.sync.google

import android.content.Context
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import org.mariotaku.twidere.extension.model.filename
import org.mariotaku.twidere.extension.model.readMimeMessageFrom
import org.mariotaku.twidere.extension.model.writeMimeMessageTo
import org.mariotaku.twidere.model.Draft
import org.mariotaku.twidere.util.sync.FileBasedDraftsSyncAction
import org.mariotaku.twidere.util.tempFileInputStream
import java.io.IOException
import java.util.*


internal class GoogleDriveDraftsSyncAction(
        context: Context,
        val drive: Drive
) : FileBasedDraftsSyncAction<DriveFileInfo>(context) {

    val draftsFolderName = "Drafts"
    val draftMimeType = "message/rfc822"

    private lateinit var folderId: String
    private val files = drive.files()

    @Throws(IOException::class)
    override fun Draft.saveToRemote(): DriveFileInfo {
        tempFileInputStream(context) { os ->
            this.writeMimeMessageTo(context, os)
        }.use {
            val driveId = this.remote_extras
            val fileConfig: (File) -> Unit = {
                it.modifiedTime = DateTime(timestamp)
            }
            val file = if (driveId != null) {
                drive.files().performUpdate(driveId, filename, draftMimeType, stream = it, fileConfig = fileConfig)
            } else {
                drive.updateOrCreate(name = filename, mimeType = draftMimeType, parent = folderId,
                        spaces = appDataFolderSpace, stream = it, fileConfig = fileConfig)
            }
            return DriveFileInfo(file.id, file.name, Date(file.modifiedTime.value))
        }
    }

    @Throws(IOException::class)
    override fun Draft.loadFromRemote(info: DriveFileInfo): Boolean {
        val get = files.get(info.fileId)
        get.executeMediaAsInputStream().use {
            val parsed = this.readMimeMessageFrom(context, it)
            if (parsed) {
                this.timestamp = info.draftTimestamp
                this.unique_id = info.draftFileName.substringBeforeLast(".eml")
                this.remote_extras = info.fileId
            }
            return parsed
        }
    }

    @Throws(IOException::class)
    override fun removeDrafts(list: List<DriveFileInfo>): Boolean {
        val batch = drive.batch()
        val callback = SimpleJsonBatchCallback<Void>()
        list.forEach { info ->
            files.delete(info.fileId).queue(batch, callback)
        }
        batch.execute()
        return true
    }

    @Throws(IOException::class)
    override fun removeDraft(info: DriveFileInfo): Boolean {
        files.delete(info.fileId).execute()
        return true
    }

    override val DriveFileInfo.draftTimestamp: Long get() = this.modifiedDate.time

    override val DriveFileInfo.draftFileName: String get() = this.name

    override val DriveFileInfo.draftRemoteExtras: String? get() = this.fileId

    @Throws(IOException::class)
    override fun listRemoteDrafts(): List<DriveFileInfo> {
        val result = ArrayList<DriveFileInfo>()
        var nextPageToken: String? = null
        do {
            val listResult = files.basicList(appDataFolderSpace).apply {
                this.q = "'$folderId' in parents and mimeType = '$draftMimeType' and trashed = false"
                if (nextPageToken != null) {
                    this.pageToken = nextPageToken
                }
            }.execute()
            listResult.files.filter { file ->
                file.mimeType == draftMimeType
            }.mapTo(result) { file ->
                DriveFileInfo(file.id, file.name, Date(file.modifiedTime.value))
            }
            nextPageToken = listResult.nextPageToken
        } while (nextPageToken != null)
        return result
    }

    override fun setup(): Boolean {
        folderId = drive.getFileOrCreate(name = draftsFolderName, mimeType = folderMimeType,
                parent = appDataFolderName, spaces = appDataFolderSpace,
                conflictResolver = ::resolveFoldersConflict).id
        return true
    }

}