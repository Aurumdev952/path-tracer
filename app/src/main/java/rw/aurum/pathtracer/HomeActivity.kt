package rw.aurum.pathtracer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The start screen: saved and received routes, and the ways to make or get one. */
class HomeActivity : ComponentActivity() {

    private val store by lazy { RouteStore(File(filesDir, "routes")) }
    private val routes = mutableStateOf<List<Route>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PathTracerTheme {
                val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) import(uri)
                }
                Home(
                    routes = routes.value,
                    photoOf = { route -> route.legs.firstOrNull()?.let { store.photo(route, it) } },
                    onRecord = { startActivity(Intent(this, RecordActivity::class.java)) },
                    onTrail = { startActivity(Intent(this, TrailActivity::class.java)) },
                    onImport = { open.launch(arrayOf("*/*")) },
                    onFollow = { FollowActivity.start(this, it) },
                    onShare = ::share,
                    onRename = { route, name ->
                        store.rename(route, name.trim().ifEmpty { route.name })
                        refresh()
                    },
                    onDelete = {
                        store.delete(it)
                        refresh()
                    },
                )
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            routes.value = withContext(Dispatchers.IO) { store.list() }
        }
    }

    /** Opens a route file shared from another app. */
    private fun handle(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        import(uri)
    }

    private fun import(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
                    input.use(store::import)
                }
            }
            result.onSuccess {
                Toast.makeText(this@HomeActivity, "Added \"${it.name}\". Tap Follow to start", Toast.LENGTH_LONG).show()
                refresh()
            }.onFailure {
                Toast.makeText(this@HomeActivity, it.message ?: "That file isn't a route", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun share(route: Route) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val safe = route.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "route" }
                File(cacheDir, "share/$safe.${RouteStore.EXTENSION}").also {
                    File(cacheDir, "share").deleteRecursively()
                    store.export(route, it)
                }
            }
            val uri = FileProvider.getUriForFile(this@HomeActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(
                    Intent.EXTRA_TEXT,
                    "Here's the way to ${route.name}. Open this file with the Path Tracer app, go to the entrance and point your camera at the sign in the photo.",
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share route"))
        }
    }
}

@Composable
private fun Home(
    routes: List<Route>,
    photoOf: (Route) -> File?,
    onRecord: () -> Unit,
    onTrail: () -> Unit,
    onImport: () -> Unit,
    onFollow: (Route) -> Unit,
    onShare: (Route) -> Unit,
    onRename: (Route, String) -> Unit,
    onDelete: (Route) -> Unit,
) {
    var renaming by remember { mutableStateOf<Route?>(null) }
    var deleting by remember { mutableStateOf<Route?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFF0B0E11)).safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                Text("Path Tracer", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text(
                    "Record the way to a room inside a big building, share it, and friends follow it in AR.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                )
                Spacer(Modifier.size(18.dp))
                Button(
                    onClick = onRecord,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) { Text("Record a new route", fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp)) }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Open route file") }
                    OutlinedButton(onClick = onTrail, modifier = Modifier.weight(1f)) { Text("Free trail") }
                }
            }
        }
        item {
            Text("My routes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (routes.isEmpty()) {
            item {
                Text(
                    "No routes yet. Record one, or open a route file a friend sent you.",
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(routes, key = { it.id }) { route ->
            RouteCard(route, photoOf(route), onFollow, onShare, { renaming = route }, { deleting = route })
        }
        item { Spacer(Modifier.size(24.dp)) }
    }

    renaming?.let { route ->
        NameDialog(
            title = "Rename route",
            initial = route.name,
            confirm = "Save",
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                onRename(route, it)
            },
        )
    }
    deleting?.let { route ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${route.name}\"?") },
            text = { Text("Friends you've already shared it with keep their copy.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(route)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RouteCard(
    route: Route,
    photo: File?,
    onFollow: (Route) -> Unit,
    onShare: (Route) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .clickable { onFollow(route) }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(photo, 64)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(route.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val floors = route.floors
                Text(
                    listOfNotNull(
                        meters(route.lengthMeters),
                        floors.takeIf { it.isNotEmpty() }?.joinToString(" → ", prefix = "floors "),
                    ).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onFollow(route) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Follow") }
            Spacer(Modifier.size(4.dp))
            OutlinedButton(onClick = { onShare(route) }) { Text("Share") }
            Box(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) { Text("More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete", color = Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
