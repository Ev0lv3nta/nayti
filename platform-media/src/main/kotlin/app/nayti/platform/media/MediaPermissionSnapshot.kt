package app.nayti.platform.media

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class MediaPermissionSnapshot(
    val scope: MediaAccessScope,
    val readImagesGranted: Boolean,
    val selectedImagesGranted: Boolean,
)

fun interface MediaPermissionReader {
    fun read(): MediaPermissionSnapshot
}

class AndroidMediaPermissionReader(
    private val context: Context,
) : MediaPermissionReader {
    override fun read(): MediaPermissionSnapshot {
        val readImagesGranted =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    context.isGranted(MediaPermissions.ReadImages)
                else -> context.isGranted(MediaPermissions.ReadExternalStorage)
            }
        val selectedImagesGranted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.isGranted(MediaPermissions.ReadVisualUserSelected)
        return MediaPermissionSnapshot(
            scope =
                MediaPermissionEvaluator.evaluate(
                    sdkInt = Build.VERSION.SDK_INT,
                    legacyReadGranted = readImagesGranted,
                    readImagesGranted = readImagesGranted,
                    selectedImagesGranted = selectedImagesGranted,
                ),
            readImagesGranted = readImagesGranted,
            selectedImagesGranted = selectedImagesGranted,
        )
    }

    private fun Context.isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

object MediaPermissionEvaluator {
    fun evaluate(
        sdkInt: Int,
        legacyReadGranted: Boolean,
        readImagesGranted: Boolean,
        selectedImagesGranted: Boolean,
    ): MediaAccessScope =
        when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && readImagesGranted ->
                MediaAccessScope.Full
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && selectedImagesGranted ->
                MediaAccessScope.Selected
            sdkInt >= Build.VERSION_CODES.TIRAMISU && readImagesGranted ->
                MediaAccessScope.Full
            sdkInt < Build.VERSION_CODES.TIRAMISU && legacyReadGranted ->
                MediaAccessScope.Full
            else -> MediaAccessScope.None
        }

    fun requestPermissions(sdkInt: Int): Array<String> =
        when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(
                    MediaPermissions.ReadImages,
                    MediaPermissions.ReadVisualUserSelected,
                )
            sdkInt >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(MediaPermissions.ReadImages)
            else -> arrayOf(MediaPermissions.ReadExternalStorage)
        }
}

object MediaPermissions {
    const val ReadExternalStorage = "android.permission.READ_EXTERNAL_STORAGE"
    const val ReadImages = "android.permission.READ_MEDIA_IMAGES"
    const val ReadVisualUserSelected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
}
