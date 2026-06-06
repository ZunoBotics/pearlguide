package com.zunobotics.okellonexus.ui.screens.language

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zunobotics.okellonexus.ui.components.NexusTopBar
import com.zunobotics.okellonexus.ui.theme.*

@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    viewModel: LanguageViewModel = hiltViewModel()
) {
    val selected by viewModel.selected.collectAsState()
    val query by viewModel.query.collectAsState()
    val codeSwitching by viewModel.codeSwitching.collectAsState()
    val languages by viewModel.filteredLanguages.collectAsState()
    val saved by viewModel.saved.collectAsState()

    LaunchedEffect(saved) { if (saved) onBack() }

    Scaffold(
        topBar = { NexusTopBar(title = "Languages", showBack = true, onBack = onBack) },
        containerColor = BackgroundWhite,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Code-Switching", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        Text(
                            "Allow mixing languages mid-conversation",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = codeSwitching,
                        onCheckedChange = { viewModel.setCodeSwitching(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricBlue)
                    )
                }
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("Save & Send to Robot (${selected.size} selected)")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (selected.isNotEmpty()) {
                Text(
                    "${selected.size} language${if (selected.size > 1) "s" else ""} selected — robot will auto-detect",
                    style = MaterialTheme.typography.bodySmall,
                    color = ElectricBlue,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search languages…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = BorderColor
                )
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(languages, key = { it.code }) { lang ->
                    val isSelected = lang.code in selected
                    LanguageRow(
                        language = lang,
                        isSelected = isSelected,
                        onClick = { viewModel.toggle(lang.code) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: RobotLanguage,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ElectricBlue.copy(alpha = 0.1f) else SurfaceWhite
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) ElectricBlue else BorderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    language.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) ElectricBlue else TextPrimary
                )
                Text(
                    language.code.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = ElectricBlue,
                    uncheckedColor = BorderColor
                )
            )
        }
    }
}
