package com.xiaozhi.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette
import com.xiaozhi.client.ui.theme.ThemeId
import com.xiaozhi.client.ui.theme.XiaozhiPalettes

@Composable
fun SettingsScreen(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SettingsViewModel(container) as T
            }
        },
    )

    LaunchedEffect(Unit) { vm.loadDraft() }

    val palette = LocalXiaozhiPalette.current
    val draft by vm.draft.collectAsStateWithLifecycle(initialValue = null)
    val message by vm.message.collectAsStateWithLifecycle()

    val d = draft ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = palette.text,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                color = palette.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_theme), palette.dim)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemeId.ALL.forEach { id ->
                    val swatch = XiaozhiPalettes.getValue(id)
                    ThemeSwatch(
                        id = id,
                        color = swatch.bg,
                        selected = d.theme == id,
                        accent = palette.accent,
                        line = palette.line,
                        labelColor = swatch.text,
                        onClick = { vm.setTheme(id) },
                    )
                }
            }

            if (message != null) {
                Text(message!!, color = palette.dim, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_cancel), color = palette.text)
                }
                Button(
                    onClick = {
                        vm.save(d)
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save), color = palette.bg)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun ThemeSwatch(
    id: ThemeId,
    color: Color,
    labelColor: Color,
    selected: Boolean,
    accent: Color,
    line: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(width = if (selected) 3.dp else 1.dp, color = if (selected) accent else line, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(id.label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
        Text(id.label, color = if (selected) accent else line, fontSize = 11.sp)
    }
}
