package com.zunobotics.okellonexus.ui.screens.people

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*
import java.io.ByteArrayOutputStream

private val ROLE_TAGS = listOf("Guest", "Regular", "Staff", "VIP")

@Composable
fun AddPersonScreen(
    personId: String = "",
    onBack: () -> Unit,
    viewModel: AddPersonViewModel = hiltViewModel()
) {
    val saved by viewModel.saved.collectAsState()
    val name by viewModel.name.collectAsState()
    val roleTag by viewModel.roleTag.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val photoBase64 by viewModel.photoBase64.collectAsState()

    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes() ?: return@runCatching
                inputStream.close()
                // Decode and re-encode as scaled-down thumbnail (~400x400 — enough for face detection)
                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val scale = 400f / maxOf(original.width, original.height)
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt(),
                    (original.height * scale).toInt(),
                    true
                )
                val out = ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                viewModel.photoBase64.value = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
    }

    LaunchedEffect(personId) { viewModel.load(personId) }
    LaunchedEffect(saved) { if (saved) onBack() }

    val isEdit = personId.isNotEmpty()

    Scaffold(
        topBar = {
            NexusTopBar(
                title = if (isEdit) "Edit Person" else "Add Person",
                showBack = true,
                onBack = onBack
            )
        },
        containerColor = BackgroundWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Photo picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PersonAvatar(photoBase64 = photoBase64, name = name.ifEmpty { "?" }, size = 72)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Upload Photo")
                    }
                    Text(
                        "Robot capture — available after Pi integration",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.name.value = it },
                label = { Text("Full Name") },
                placeholder = { Text("e.g. Sarah Nakato") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = name.isBlank()
            )

            // Role tag
            Text("Role Tag", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ROLE_TAGS.forEach { tag ->
                    FilterChip(
                        selected = roleTag == tag,
                        onClick = { viewModel.roleTag.value = tag },
                        label = { Text(tag) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = when (tag) {
                                "VIP" -> AmberGold
                                "Staff" -> ElectricBlue
                                "Regular" -> SuccessTeal
                                else -> TextSecondary.copy(alpha = 0.2f)
                            },
                            selectedLabelColor = SurfaceWhite
                        )
                    )
                }
            }

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { viewModel.notes.value = it },
                label = { Text("Notes (optional)") },
                placeholder = { Text("e.g. CEO of Kampala Properties Ltd") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Face recognition note
            Card(
                colors = CardDefaults.cardColors(containerColor = ElectricBlue.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                    Text(
                        "Face recognition is computed on the Quest 3. Photo is stored here for reference only. " +
                        "The robot will greet this person by name once face recognition is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricBlue
                    )
                }
            }

            // Save
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                enabled = name.isNotBlank()
            ) {
                Text(if (isEdit) "Update Person" else "Save Person")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
