package com.muh.usbcameraviewer.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.muh.usbcameraviewer.R

// One bundled third-party component and the license it is distributed under.
private data class Component(
    val name: String,
    val license: String,
    @param:RawRes val licenseRes: Int,
)

private val components =
    listOf(
        Component("AndroidUSBCamera (AUSBC)", "Apache License 2.0", R.raw.license_apache_2_0),
        Component("libuvc", "BSD 3-Clause License", R.raw.license_bsd_3),
        Component("libusb", "GNU LGPL 2.1", R.raw.license_lgpl_2_1),
        Component("Coil", "Apache License 2.0", R.raw.license_apache_2_0),
        Component("AndroidX / Jetpack Compose", "Apache License 2.0", R.raw.license_apache_2_0),
        Component("Kotlin", "Apache License 2.0", R.raw.license_apache_2_0),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Each license text is bundled once in res/raw; read the distinct files lazily.
    val licenseTexts =
        remember {
            components
                .map { it.licenseRes }
                .distinct()
                .associateWith { res ->
                    context.resources.openRawResource(res).bufferedReader().use { it.readText() }
                }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            components.forEach { component ->
                Text(
                    component.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    component.license,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    licenseTexts[component.licenseRes].orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }
        }
    }
}
