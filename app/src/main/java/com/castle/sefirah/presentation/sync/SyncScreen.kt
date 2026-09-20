package com.castle.sefirah.presentation.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.castle.sefirah.navigation.SyncRoute
import com.castle.sefirah.presentation.sync.components.DeviceItem
import sefirah.common.R
import sefirah.presentation.components.PullRefresh
import sefirah.presentation.screens.EmptyScreen

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    rootNavController: NavHostController,
) {
    val viewModel: SyncViewModel = hiltViewModel()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    PullRefresh(
        refreshing = isRefreshing,
        enabled = true,
        onRefresh = { viewModel.refresh() }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.available_devices)) },
                    navigationIcon = {
                        IconButton(onClick = { rootNavController.navigateUp() }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                rootNavController.navigate(SyncRoute.QrCodeScannerScreen.route)
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_scanner),
                                contentDescription = "Scan QR Code"
                            )
                        }
                    }
                )
            }
        ) { contentPadding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                Text(
                    text = stringResource(R.string.sync_screen_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )

                when {
                    discoveredDevices.isEmpty() -> {
                        EmptyScreen(message = stringResource(R.string.no_device))
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(
                                items = discoveredDevices.values.toList(),
                                key = { it.deviceId },
                            ) { device ->
                                DeviceItem(
                                    device = device,
                                    onClick = {
                                        viewModel.pair(device, rootNavController)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
