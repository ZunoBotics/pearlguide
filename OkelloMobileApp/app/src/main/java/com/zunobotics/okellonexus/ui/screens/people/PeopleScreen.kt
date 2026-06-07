package com.zunobotics.okellonexus.ui.screens.people

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import com.zunobotics.okellonexus.ui.components.ConfirmDialog
import com.zunobotics.okellonexus.ui.components.EmptyState
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onAddPerson: () -> Unit,
    onViewPerson: (String) -> Unit,
    viewModel: PeopleViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    var deleteTarget by remember { mutableStateOf<FaceProfile?>(null) }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Remove Person",
            message = "Remove \"${target.name}\" from the face database? This cannot be undone.",
            onConfirm = { viewModel.delete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }

    Scaffold(
        topBar = { NexusTopBar(title = "People", showBack = true, onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddPerson, containerColor = ElectricBlue) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Person", tint = SurfaceWhite)
            }
        },
        containerColor = BackgroundWhite
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Default.People,
                    message = "No people enrolled yet.\nAdd known visitors, VIPs, or staff.",
                    actionLabel = "Add Person",
                    onAction = onAddPerson,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    PersonCard(
                        profile = profile,
                        onTap = { onViewPerson(profile.id) },
                        onAssignVip = { viewModel.assignVip(profile.id) },
                        onClearVip = { viewModel.clearVip() },
                        onDelete = { deleteTarget = profile }
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonCard(
    profile: FaceProfile,
    onTap: () -> Unit,
    onAssignVip: () -> Unit,
    onClearVip: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isCurrentVip) ElectricBlue.copy(alpha = 0.07f) else SurfaceWhite
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(if (profile.isCurrentVip) 0.dp else 2.dp),
        border = if (profile.isCurrentVip) androidx.compose.foundation.BorderStroke(2.dp, ElectricBlue) else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                PersonAvatar(photoBase64 = profile.photoBase64, name = profile.name, size = 64)
                if (profile.isCurrentVip) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        color = ElectricBlue,
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp).padding(3.dp)
                        )
                    }
                }
            }

            Text(
                profile.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1
            )

            RoleChip(profile.roleTag)

            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!profile.isCurrentVip) {
                        DropdownMenuItem(
                            text = { Text("Assign as VIP") },
                            onClick = { onAssignVip(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = AmberGold) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Remove VIP") },
                            onClick = { onClearVip(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.StarBorder, contentDescription = null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = ErrorRed) },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) }
                    )
                }
            }
        }
    }
}

@Composable
fun PersonAvatar(photoBase64: String, name: String, size: Int) {
    val bitmap = remember(photoBase64) {
        if (photoBase64.isNotEmpty()) {
            runCatching {
                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(ElectricBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = ElectricBlue,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RoleChip(roleTag: String) {
    val (bg, fg) = when (roleTag) {
        "VIP" -> AmberGold to Color.White
        "Staff" -> ElectricBlue to Color.White
        "Regular" -> SuccessTeal to Color.White
        else -> TextSecondary.copy(alpha = 0.15f) to TextSecondary
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            roleTag,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}
