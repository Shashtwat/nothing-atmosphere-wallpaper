package io.github.shashtwat.atmosphere

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import io.github.shashtwat.atmosphere.ui.NButton
import io.github.shashtwat.atmosphere.ui.NtFooter
import io.github.shashtwat.atmosphere.ui.nt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The folder the grid shows: one the user picked (a document tree, remembered across launches),
 * or Pictures/Wallpapers through MediaStore until they pick one.
 */
object WallpaperFolder {
    const val PATH = "Pictures/Wallpapers/"
    private const val K_TREE = "folder_tree"

    fun tree(ctx: Context): Uri? = AtmosphereSettings.prefs(ctx).getString(K_TREE, null)?.let(Uri::parse)
        ?.takeIf { t -> ctx.contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission } }

    fun setTree(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AtmosphereSettings.prefs(ctx).edit().putString(K_TREE, uri.toString()).apply()
    }

    /** The folder's display name. */
    fun name(ctx: Context): String {
        val t = tree(ctx) ?: return "Pictures/Wallpapers"
        val doc = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        return runCatching {
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Folder"
    }

    /** Images in the picked folder, newest first. */
    fun treeImages(ctx: Context, t: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        val found = mutableListOf<Pair<Uri, Long>>()
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1)?.startsWith("image/") != true) continue
                found += DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)) to c.getLong(2)
            }
        }
        return found.sortedByDescending { it.second }.map { it.first }
    }

    /** READ_MEDIA_IMAGES from Android 13, the old storage permission before. */
    val PERMISSION: String =
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun images(ctx: Context): List<Uri> {
        val out = mutableListOf<Uri>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // RELATIVE_PATH is API 29; before that, match the file path.
        val (where, args) = if (android.os.Build.VERSION.SDK_INT >= 29)
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?" to arrayOf(PATH)
        else "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/$PATH%")
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media._ID),
            where, args,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c -> while (c.moveToNext()) out += ContentUris.withAppendedId(uri, c.getLong(0)) }
        return out
    }
}

/** Grid of the chosen folder (or Pictures/Wallpapers); tapping a photo selects it. */
@Composable
fun WallpaperGrid(selected: Uri?, enabled: Boolean, onPick: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var tree by remember { mutableStateOf(WallpaperFolder.tree(ctx)) }
    var granted by remember { mutableStateOf(WallpaperFolder.granted(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { WallpaperFolder.setTree(ctx, uri); tree = WallpaperFolder.tree(ctx) }
    }
    val folderName = remember(tree) { WallpaperFolder.name(ctx) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(folderName, fontSize = 15.sp, color = nt.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("Choose folder", fontSize = 14.sp, color = nt.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { pickFolder.launch(null) }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    val t = tree
    if (t == null && !granted) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            NButton("Show Pictures/Wallpapers", { ask.launch(WallpaperFolder.PERMISSION) }, Modifier.fillMaxWidth(), primary = false)
        }
        NtFooter("Or choose any folder above.")
        return
    }
    val images by produceState<List<Uri>?>(null, t, granted) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (t != null) WallpaperFolder.treeImages(ctx, t) else WallpaperFolder.images(ctx) }.getOrDefault(emptyList())
        }
    }
    val list = images ?: return
    if (list.isEmpty()) { NtFooter("No images in this folder."); return }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in list.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (uri in row) Thumb(uri, uri == selected, Modifier.weight(1f)) { if (enabled) onPick(uri) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Thumb(uri: Uri, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            (if (android.os.Build.VERSION.SDK_INT >= 29)
                runCatching { ctx.contentResolver.loadThumbnail(uri, Size(320, 640), null) }.getOrNull() else null)
                ?: runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    }
                }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.aspectRatio(9f / 19.5f).clip(shape).background(nt.surfaceTertiary)
            .then(if (selected) Modifier.border(3.dp, nt.iconPrimary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
