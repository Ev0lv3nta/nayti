package app.nayti.ml.runtime.pack

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.InputStream

class SafModelPackSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
) : ModelPackSource {
    override fun declaredLengthBytes(): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column).takeIf { it >= 0 } else null
        }
    } catch (_: IllegalArgumentException) {
        null // Size metadata is optional; stream bounds and signature checks remain mandatory.
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: SecurityException) {
        null // openStream still checks the actual content grant.
    }

    override fun openStream(): InputStream =
        contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open model pack URI")
}

object AndroidModelPackPolicy {
    fun current(appVersionCode: Long, engineApi: Long = 1): ModelPackPolicy =
        ModelPackPolicy(
            appVersionCode = appVersionCode,
            engineApi = engineApi,
            androidApi = Build.VERSION.SDK_INT.toLong(),
            supportedAbis = Build.SUPPORTED_ABIS.toSet(),
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
        )
}

class AndroidModelPackStorageBudget(context: android.content.Context) : ModelPackStorageBudget {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    override fun allocatableBytes(path: java.nio.file.Path): Long {
        val storageUuid = storageManager.getUuidForPath(path.toFile())
        return storageManager.getAllocatableBytes(storageUuid)
    }
}
