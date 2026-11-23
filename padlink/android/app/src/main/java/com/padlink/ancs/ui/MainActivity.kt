package com.padlink.ancs.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padlink.ancs.ble.AncsProtocol
import com.padlink.ancs.ble.BleCentralService
import com.padlink.ancs.ble.BleServiceState
import com.padlink.ancs.ble.ConnectionState
import com.padlink.ancs.ble.connectionStateLabel
import com.padlink.ancs.launcher.PhoneLauncher
import com.padlink.ancs.model.AppNotification
import com.padlink.ancs.model.NotificationStore
import com.padlink.ancs.notif.NotifForwarder
import com.padlink.ancs.prefs.Prefs
import com.padlink.ancs.prefs.Settings as PrefSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var openUid by mutableStateOf(-1)
    private var bonding by mutableStateOf(false)
    private var bondReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestInitialPermissions()
        registerBondReceiver()
        openUid = intent?.getIntExtra(NotifForwarder.EXTRA_OPEN_UID, -1) ?: -1
        setContent {
            PadLinkApp(
                openUid = openUid,
                bonding = bonding,
                onBond = { address -> requestBond(address) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uid = intent.getIntExtra(NotifForwarder.EXTRA_OPEN_UID, -1)
        if (uid >= 0) openUid = uid
    }

    override fun onDestroy() {
        bondReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        bondReceiver = null
        super.onDestroy()
    }

    /** 一次性申请运行权限（技术规格 §5.2）。 */
    private fun requestInitialPermissions() {
        val perms: Array<String> = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            else -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun registerBondReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE -> bonding = false
                }
            }
        }
        bondReceiver = receiver
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    /** 从扫描列表点击「配对」：发起系统配对（配对成功由服务保存地址并连接）。 */
    private fun requestBond(address: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "invalid address $address", e)
            return
        }
        bonding = true
        try {
            if (!device.createBond()) {
                bonding = false
            }
        } catch (e: Exception) {
            bonding = false
            Log.w("MainActivity", "createBond failed", e)
        }
    }
}

// ———————————————————— 应用壳 ————————————————————

private enum class Screen { PAIRING, STATUS, NOTIFICATIONS, SETTINGS }

private val PadLinkBlue = Color(0xFF0B57D0)

@Composable
private fun PadLinkApp(openUid: Int, bonding: Boolean, onBond: (String) -> Unit) {
    var screen by remember { mutableStateOf(Screen.PAIRING) }
    var selectedUid by remember { mutableStateOf<Int?>(null) }

    val bleState by BleCentralService.state.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val notifications by NotificationStore.flow.collectAsState()

    // 从系统通知点击进入：跳到通知中心并打开对应详情
    LaunchedEffect(openUid) {
        if (openUid >= 0) {
            screen = Screen.NOTIFICATIONS
            selectedUid = openUid
        }
    }

    val selected = selectedUid?.let { uid -> notifications.firstOrNull { it.uid == uid } }

    // 详情页系统返回键回列表
    BackHandler(enabled = selected != null) { selectedUid = null }

    MaterialTheme(colorScheme = lightColorScheme(primary = PadLinkBlue)) {
        Scaffold(
            bottomBar = {
                if (selected == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen == Screen.PAIRING,
                            onClick = { screen = Screen.PAIRING },
                            icon = { Icon(Icons.Outlined.Bluetooth, contentDescription = "配对") },
                            label = { Text("配对") }
                        )
                        NavigationBarItem(
                            selected = screen == Screen.STATUS,
                            onClick = { screen = Screen.STATUS },
                            icon = { Icon(Icons.Outlined.Info, contentDescription = "状态") },
                            label = { Text("状态") }
                        )
                        NavigationBarItem(
                            selected = screen == Screen.NOTIFICATIONS,
                            onClick = { screen = Screen.NOTIFICATIONS },
                            icon = { Icon(Icons.Outlined.Notifications, contentDescription = "通知") },
                            label = { Text("通知") }
                        )
                        NavigationBarItem(
                            selected = screen == Screen.SETTINGS,
                            onClick = { screen = Screen.SETTINGS },
                            icon = { Icon(Icons.Outlined.SettingsIcon, contentDescription = "设置") },
                            label = { Text("设置") }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when {
                    selected != null -> NotificationDetailScreen(selected!!, onBack = { selectedUid = null })
                    screen == Screen.PAIRING -> PairingScreen(bleState, settings, bonding, onBond)
                    screen == Screen.STATUS -> StatusScreen(bleState, settings)
                    screen == Screen.NOTIFICATIONS -> NotificationListScreen(notifications, onOpen = { selectedUid = it })
                    else -> SettingsScreen()
                }
            }
        }
    }
}

// ———————————————————— 配对引导页 ————————————————————

@Composable
private fun PairingScreen(
    bleState: BleServiceState,
    settings: PrefSettings,
    bonding: Boolean,
    onBond: (String) -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("配对 iPhone", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "首次使用需与 iPhone 完成蓝牙系统配对（iOS 地址随机化，只需配对一次，之后自动重连）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (!bleState.bluetoothEnabled) {
            WarningCard("蓝牙未开启：请先打开平板蓝牙（下方「去系统设置配对」可直接打开）。")
        }

        when (bleState.connection) {
            ConnectionState.BONDING -> StatusCard(
                "配对中…",
                "请在 iPhone 弹出的配对请求上点「配对」，并核对两端配对码一致。"
            )
            ConnectionState.CONNECTING -> StatusCard("连接中…", "正在连接已配对的 iPhone…")
            ConnectionState.DISCOVERING -> StatusCard("发现服务…", "正在读取 iPhone 的 ANCS 服务…")
            ConnectionState.SUBSCRIBING -> StatusCard("订阅通知…", "正在订阅通知源…")
            ConnectionState.READY -> StatusCard(
                "已连接 ✓",
                "iPhone：${bleState.deviceName ?: "未知"}（${bleState.deviceAddress ?: ""}）"
            )
            else -> Unit
        }
        if (bonding && bleState.connection != ConnectionState.BONDING) {
            StatusCard("正在发起配对…", "请在 iPhone 上确认配对请求。")
        }
        bleState.error?.let { WarningCard(it) }
        Spacer(Modifier.height(16.dp))

        if (settings.deviceAddress != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("已配对设备", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${settings.deviceName ?: "iPhone"}\n${settings.deviceAddress}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { BleCentralService.connectSaved(context) }, enabled = bleState.connection != ConnectionState.READY) {
                            Text("连接")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { BleCentralService.disconnect(context) }) {
                            Text("断开")
                        }
                    }
                    TextButton(onClick = { Prefs.setDevice(null, null) }) {
                        Text("忘记此设备")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("附近设备（仅首次配对）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { BleCentralService.startScan(context) },
                enabled = !bleState.scanActive && bleState.bluetoothEnabled
            ) {
                Text(if (bleState.scanActive) "扫描中…（60 秒）" else "开始扫描")
            }
            if (bleState.scanActive) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { BleCentralService.stopScan(context) }) { Text("停止") }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (bleState.scanActive && bleState.scanDevices.isEmpty()) {
            Text(
                "正在扫描附近蓝牙设备…\n提示：iPhone 广播名通常以「iPhone」开头；安装了 iPhone 端 PadLink 助手的设备会置顶显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (d in bleState.scanDevices) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.advertisesPadLinkService) {
                            Text("✓ 检测到 iPhone 端 PadLink 助手", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B873B))
                        }
                    }
                    TextButton(onClick = { onBond(d.address) }) { Text("配对") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w("MainActivity", "open bluetooth settings failed", e)
                }
            }
        ) {
            Text("去系统设置配对")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "说明：ANCS 即 iPhone 的「共享系统通知」功能。系统配对完成后，PadLink 会自动连接并开始转发通知，无需在 iPhone 上安装任何 App（可选安装 PadLink 助手以便扫描时识别）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ———————————————————— 状态页 ————————————————————

@Composable
private fun StatusScreen(bleState: BleServiceState, settings: PrefSettings) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("状态", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        StatusCard("蓝牙", if (bleState.bluetoothEnabled) "已开启" else "未开启")
        StatusCard("连接状态", connectionStateLabel(bleState.connection))
        StatusCard("iPhone", bleState.deviceName ?: settings.deviceName ?: "未配对")
        StatusCard(
            "最近同步",
            bleState.lastSyncAt?.let { formatTime(it) } ?: "暂无"
        )
        bleState.error?.let { WarningCard(it) }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ColorOS / 国产 ROM 保活白名单", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "若锁屏后断连或收不到通知，请将 PadLink 加入系统白名单：\n\n" +
                        "1. 打开「手机管家 / 安全中心」→「应用管理」→「自启动管理」，允许 PadLink 自启动；\n" +
                        "2. 「电池」→「应用耗电管理」，将 PadLink 设为「允许后台运行 / 不限制」；\n" +
                        "3. 多任务界面将 PadLink 卡片下拉锁定。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ———————————————————— 通知中心 ————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationListScreen(notifications: List<AppNotification>, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "通知中心",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )
        Text(
            "共 ${notifications.size} 条 · 断线后清空",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        if (notifications.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无通知\n连接 iPhone 后将自动同步",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(notifications.asReversed(), key = { it.uid }) { n ->
                    Card(
                        onClick = { onOpen(n.uid) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(
                                    PhoneLauncher.displayName(n.appIdentifier),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    formatTime(n.receivedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                n.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ———————————————————— 通知详情 ————————————————————

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationDetailScreen(n: AppNotification, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("通知详情", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            PhoneLauncher.displayName(n.appIdentifier),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            formatTime(n.receivedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        n.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }
        n.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        n.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
        }

        // 通知动作（正向/负向）：点击回写 ANCS Control Point
        if (n.hasPositiveAction || n.hasNegativeAction) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n.hasPositiveAction && !n.positiveActionLabel.isNullOrBlank()) {
                    Button(onClick = {
                        BleCentralService.performAction(context, n.uid, AncsProtocol.ACTION_ID_POSITIVE)
                    }) {
                        Text(n.positiveActionLabel)
                    }
                }
                if (n.hasNegativeAction && !n.negativeActionLabel.isNullOrBlank()) {
                    OutlinedButton(onClick = {
                        BleCentralService.performAction(context, n.uid, AncsProtocol.ACTION_ID_NEGATIVE)
                    }) {
                        Text(n.negativeActionLabel)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 在平板打开对应本地应用（微信/QQ）
        n.appIdentifier?.takeIf { it.isNotBlank() }?.let { appId ->
            Button(
                onClick = { PhoneLauncher.launch(context, appId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("在平板打开${PhoneLauncher.displayName(appId)}")
            }
        }
    }
}

// ———————————————————— 设置页 ————————————————————

@Composable
private fun SettingsScreen() {
    val settings by Prefs.settings.collectAsState()
    val notifications by NotificationStore.flow.collectAsState()
    val appIds = notifications.mapNotNull { it.appIdentifier }.toSet()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SettingSwitchRow(
            title = "自动重连 iPhone",
            subtitle = "断线后按指数退避自动重连（5 秒起，最多每 60 秒尝试一次）",
            checked = settings.autoReconnect,
            enabled = true,
            onCheckedChange = { Prefs.setAutoReconnect(it) }
        )
        SettingSwitchRow(
            title = "自动连 iPhone 热点",
            subtitle = "可选功能，一期未实现",
            checked = settings.autoHotspot,
            enabled = false,
            onCheckedChange = null
        )
        Spacer(Modifier.height(16.dp))

        Text("通知正文最大长度：${settings.maxMessageLength} 字", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = settings.maxMessageLength.toFloat(),
            onValueChange = { Prefs.setMaxMessageLength(it.roundToInt()) },
            valueRange = 100f..2000f,
            steps = 18
        )
        Spacer(Modifier.height(16.dp))

        Text("应用黑名单", style = MaterialTheme.typography.titleMedium)
        Text(
            "被拉黑的应用不再弹出系统通知，但通知仍保存在通知中心。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (appIds.isEmpty()) {
            Text(
                "暂无应用记录（连接 iPhone 收到通知后显示）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for (appId in appIds) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(PhoneLauncher.displayName(appId), style = MaterialTheme.typography.titleSmall)
                        Text(appId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.blacklist.contains(appId),
                        onCheckedChange = { Prefs.setBlacklisted(appId, it) }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("收不到通知？", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 授予通知权限（系统设置 → 应用 → PadLink → 通知）；\n" +
                        "2. 将 PadLink 加入电池白名单并允许自启动（ColorOS：手机管家 → 自启动管理）；\n" +
                        "3. 保持服务通知存在，勿在通知栏划掉。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "PadLink 一期 · v0.1.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ———————————————————— 通用组件 ————————————————————

@Composable
private fun StatusCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(96.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BluetoothDisabled, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
