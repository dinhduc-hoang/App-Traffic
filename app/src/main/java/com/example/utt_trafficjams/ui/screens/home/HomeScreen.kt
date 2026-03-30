package com.example.utt_trafficjams.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.utt_trafficjams.data.model.ChatMessage
import com.example.utt_trafficjams.ui.theme.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// ==============================
// HOME SCREEN — 2 Tabs: Bản đồ | Chat AI
// ==============================

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val context       = LocalContext.current
    val messages      by vm.messages.collectAsState()
    val isLoading     by vm.isLoading.collectAsState()
    val isListening   by vm.isListening.collectAsState()
    val chatReady     by vm.isChatReady.collectAsState()

    var selectedTab   by remember { mutableStateOf(0) } // 0=Map, 1=Chat
    var inputText     by remember { mutableStateOf("") }
    val listState     = rememberLazyListState()
    val scope         = rememberCoroutineScope()

    // Permission launcher for Microphone
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.toggleListening()
    }

    // Auto-scroll khi tin mới đến
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    // Chuyển sang chat tab khi có tin mới
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && selectedTab == 0) {
            // không tự chuyển tab trừ khi user đang ở voice
        }
    }

    LaunchedEffect(Unit) {
        vm.openGoogleMapsRequests.collect { request ->
            openGoogleMapsNavigation(context, request)
            selectedTab = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // ── App Header ──────────────────────────────────────────
        HomeAppBar(
            chatReady = chatReady,
            isListening   = isListening
        )

        // ── Tab Selector ────────────────────────────────────────
        HomeTabBar(
            selectedTab    = selectedTab,
            onTabSelected  = { selectedTab = it },
            hasNewMessages = messages.isNotEmpty()
        )

        // ── Content ─────────────────────────────────────────────
        when (selectedTab) {
            0 -> MapTab(
                onChatClick = { selectedTab = 1 },
                onLocationUpdated = { latLng ->
                    vm.updateCurrentLocationFromMap(latLng.latitude, latLng.longitude)
                },
                onVoiceClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        selectedTab = 1
                        if (!isListening) vm.toggleListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
            1 -> ChatTab(
                messages      = messages,
                listState     = listState,
                isLoading     = isLoading,
                isListening   = isListening,
                chatReady = chatReady,
                inputText     = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    vm.sendMessage(inputText)
                    inputText = ""
                },
                onMicClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        vm.toggleListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    }
}

// ==============================
// App Bar
// ==============================
@Composable
private fun HomeAppBar(chatReady: Boolean, isListening: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(PrimaryAmber),
            contentAlignment = Alignment.Center
        ) { Text("🚦", fontSize = 18.sp) }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "UTT Traffic",
                style = MaterialTheme.typography.titleMedium,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = if (chatReady) StatusGreen else StatusRed
                val label    = if (isListening) "ĐANG NGHE..." else if (chatReady) "LIVE • LOCAL" else "ĐANG KẾT NỐI"
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = dotColor, letterSpacing = 0.5.sp)
            }
        }

        Surface(shape = RoundedCornerShape(16.dp), color = CardDark) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, null, tint = PrimaryAmber, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("HÀ NỘI", style = MaterialTheme.typography.labelSmall, color = TextWhite)
            }
        }
    }
}

// ==============================
// Tab Bar: 🗺️ Bản Đồ | 💬 Chat AI
// ==============================
@Composable
private fun HomeTabBar(
    selectedTab   : Int,
    onTabSelected : (Int) -> Unit,
    hasNewMessages: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TabItem(
            label     = "🗺️  Bản Đồ",
            selected  = selectedTab == 0,
            onClick   = { onTabSelected(0) },
            modifier  = Modifier.weight(1f)
        )
        TabItem(
            label     = "💬  Chat AI",
            selected  = selectedTab == 1,
            onClick   = { onTabSelected(1) },
            modifier  = Modifier.weight(1f),
            badge     = hasNewMessages
        )
    }

    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TabItem(
    label   : String,
    selected: Boolean,
    onClick : () -> Unit,
    modifier: Modifier = Modifier,
    badge   : Boolean  = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrimaryAmber else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (selected) Color.Black else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (badge && !selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(StatusGreen)
            )
        }
    }
}

// ==============================
// TAB 1 — BẢN ĐỒ
// ==============================
@Composable
private fun MapTab(
    onChatClick : () -> Unit,
    onLocationUpdated: (LatLng) -> Unit,
    onVoiceClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // Title
        Text(
            "AI Traffic Assistant",
            style      = MaterialTheme.typography.headlineMedium,
            color      = TextWhite,
            fontWeight = FontWeight.Bold
        )
        Text(
            "VIRTUAL INTELLIGENCE",
            style        = MaterialTheme.typography.labelSmall,
            color        = TextSecondary,
            letterSpacing = 1.5.sp
        )

        Spacer(Modifier.height(12.dp))

        // Map Card
        TrafficMapCard(onLocationUpdated = onLocationUpdated)

        Spacer(Modifier.height(20.dp))

        // Quick action buttons row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Open Chat Button
            Button(
                onClick  = onChatClick,
                modifier = Modifier.weight(1f).height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CardDark)
            ) {
                Icon(Icons.Default.Chat, null, tint = PrimaryAmber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat AI", color = TextWhite, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            // Voice Button
            Button(
                onClick  = onVoiceClick,
                modifier = Modifier.weight(1f).height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = PrimaryAmber)
            ) {
                Icon(Icons.Default.Mic, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Voice AI", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Quick grid
        Text(
            "Chức năng nhanh",
            style      = MaterialTheme.typography.titleMedium,
            color      = TextWhite,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionCard(Icons.Default.Route,   "Lộ Trình",  "Tránh kẹt xe",        Modifier.weight(1f))
            QuickActionCard(Icons.Default.Search,  "Tra Cứu",   "Nghị định 100",        Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionCard(Icons.Default.Warning, "Cảnh Báo",  "Điểm nóng giao thông", Modifier.weight(1f))
            QuickActionCard(Icons.Default.Person,  "Cá Nhân",   "Lịch trình di chuyển", Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ==============================
// TAB 2 — CHAT AI + VOICE
// ==============================
@Composable
private fun ChatTab(
    messages      : List<ChatMessage>,
    listState     : androidx.compose.foundation.lazy.LazyListState,
    isLoading     : Boolean,
    isListening   : Boolean,
    chatReady     : Boolean,
    inputText     : String,
    onInputChange : (String) -> Unit,
    onSend        : () -> Unit,
    onMicClick    : () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // ── Connection Status ────────────────────────────────────
        ChatStatusBar(chatReady = chatReady)

        // ── Voice Indicator (khi đang nghe) ─────────────────────
        if (isListening) {
            VoiceListeningBanner()
        }

        // ── Messages ─────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding      = PaddingValues(vertical = 12.dp)
        ) {
            if (isLoading && messages.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryAmber, modifier = Modifier.size(28.dp))
                    }
                }
            }

            if (messages.isEmpty() && !isLoading) {
                item { WelcomeBubble() }
            }

            items(messages, key = { it.id.ifEmpty { it.timestamp.toString() } }) { msg ->
                ChatBubble(message = msg)
            }

            if (isLoading && messages.isNotEmpty()) {
                item { TypingIndicator() }
            }
        }

        // ── Input + Mic ─────────────────────────────────────────
        ChatInputRow(
            inputText   = inputText,
            onInputChange = onInputChange,
            isListening = isListening,
            onSend      = onSend,
            onMicClick  = onMicClick
        )
    }
}

// ==============================
// Status bar chat
// ==============================
@Composable
private fun ChatStatusBar(chatReady: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (chatReady) StatusGreen else StatusRed)
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.SmartToy, null, tint = PrimaryAmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Trợ lý Traffic",
                style      = MaterialTheme.typography.labelMedium,
                color      = TextWhite,
                fontWeight = FontWeight.Bold
            )
        }
        Surface(shape = RoundedCornerShape(8.dp), color = PrimaryAmber.copy(alpha = 0.15f)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Cloud, null, tint = PrimaryAmber, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (chatReady) "Live" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryAmber
                )
            }
        }
    }
}

// ==============================
// Banner đang nghe voice
// ==============================
@Composable
private fun VoiceListeningBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable<Float>(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StatusRed.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Mic,
            null,
            tint     = StatusRed.copy(alpha = alpha),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Đang nghe giọng nói... Hãy nói câu hỏi của bạn",
            style  = MaterialTheme.typography.bodyMedium,
            color  = StatusRed.copy(alpha = alpha),
            fontWeight = FontWeight.Medium
        )
    }
}

// ==============================
// Chat Input Row: TextField + Mic + Send
// ==============================
@Composable
private fun ChatInputRow(
    inputText    : String,
    onInputChange: (String) -> Unit,
    isListening  : Boolean,
    onSend       : () -> Unit,
    onMicClick   : () -> Unit
) {
    // Mic pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = if (isListening) 1.2f else 1f,
        animationSpec = if (isListening) {
            infiniteRepeatable<Float>(
                animation  = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            infiniteRepeatable<Float>(
                animation  = tween(1, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        },
        label = "mic_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Mic Button (large, standalone) ──────────────────
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .scale(micScale)
                    .clip(CircleShape)
                    .background(
                        if (isListening) StatusRed else CardDark
                    )
                    .clickable { onMicClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isListening) "Dừng ghi âm" else "Ghi âm giọng nói",
                    tint     = if (isListening) Color.White else PrimaryAmber,
                    modifier = Modifier.size(26.dp)
                )
            }

            // ── Text input ──────────────────────────────────────
            Surface(
                shape    = RoundedCornerShape(26.dp),
                color    = CardDark,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier          = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value         = inputText,
                        onValueChange = onInputChange,
                        placeholder   = {
                            Text(
                                if (isListening) "Đang nghe..." else "Nhắn tin với AI...",
                                color = if (isListening) PrimaryAmber.copy(alpha = 0.6f) else TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors   = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor   = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor   = Color.Transparent,
                            cursorColor             = PrimaryAmber
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextWhite),
                        singleLine = false,
                        maxLines   = 3
                    )

                    // Send button
                    val canSend = inputText.isNotBlank()
                    IconButton(
                        onClick  = onSend,
                        enabled  = canSend,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (canSend) PrimaryAmber else CardDarkLight)
                    ) {
                        Icon(
                            Icons.Default.Send, "Gửi",
                            tint     = if (canSend) Color.Black else TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Hint text
        Text(
            text      = if (isListening) "Nhấn 🎤 để dừng nghe" else "Nhấn 🎤 để nói • Nhấn ✈ để gửi",
            style     = MaterialTheme.typography.labelSmall,
            color     = TextTertiary,
            modifier  = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

// ==============================
// Welcome Bubble (Tin chào mặc định)
// ==============================
@Composable
private fun WelcomeBubble() {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape    = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color    = CardDark,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, null, tint = PrimaryAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Trợ lý Traffic AI", style = MaterialTheme.typography.labelLarge,
                        color = PrimaryAmber, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Xin chào! Tôi là trợ lý giao thông thông minh 🤖\nBạn có thể:",
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = TextWhite,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "💬 Nhắn tin: gõ câu hỏi vào ô bên dưới",
                    "🎤 Giọng nói: nhấn nút mic màu đỏ",
                    "🚗 Hỏi về kẹt xe, lộ trình tối ưu",
                    "⚖️ Tra cứu luật giao thông, mức phạt"
                ).forEach { hint ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(hint, style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary, lineHeight = 18.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Trợ lý • Vừa xong", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

// ==============================
// Chat Bubble
// ==============================
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser  = message.sender == "user"
    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isUser) 16.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 16.dp,
                bottomStart = 16.dp,
                bottomEnd   = 16.dp
            ),
            color    = if (isUser) PrimaryAmber else CardDark,
            modifier = if (isUser) Modifier else Modifier.fillMaxWidth(0.9f)
        ) {
            Text(
                text       = message.text,
                style      = MaterialTheme.typography.bodyMedium,
                color      = if (isUser) Color.Black else TextWhite,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                modifier   = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                lineHeight = 20.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isUser) {
                Icon(Icons.Default.SmartToy, null, tint = TextTertiary, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

// ==============================
// Typing Indicator (3 chấm nhảy)
// ==============================
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(CardDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.SmartToy, null, tint = PrimaryAmber, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue  = 0.5f,
                targetValue   = 1.0f,
                animationSpec = infiniteRepeatable<Float>(
                    animation  = tween(400, delayMillis = index * 150, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(PrimaryAmber)
            )
        }
        Spacer(Modifier.width(2.dp))
        Text("AI đang trả lời...", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

// ==============================
// Map Card (Tab Bản đồ)
// ==============================
@Composable
private fun TrafficMapCard(onLocationUpdated: (LatLng) -> Unit) {
    val context = LocalContext.current
    val hanoi = remember { LatLng(21.0278, 105.8342) }
    var hasLocationPermission by remember {
        mutableStateOf(context.hasMapLocationPermission())
    }
    var currentLocation by remember {
        mutableStateOf<LatLng?>(null)
    }
    var hasRequestedLocationPermission by remember {
        mutableStateOf(false)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        hasLocationPermission =
            grantedMap[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grantedMap[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                context.hasMapLocationPermission()
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(hanoi, 12.5f)
    }
    val mapProperties = remember(hasLocationPermission) {
        MapProperties(
            mapType = com.google.maps.android.compose.MapType.NORMAL,
            isTrafficEnabled = true,
            isBuildingEnabled = true,
            isMyLocationEnabled = hasLocationPermission
        )
    }
    val mapUiSettings = remember(hasLocationPermission) {
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = hasLocationPermission,
            compassEnabled = true
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        hasLocationPermission = context.hasMapLocationPermission()
        if (!hasLocationPermission && !hasRequestedLocationPermission) {
            hasRequestedLocationPermission = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@LaunchedEffect

        val latestLocation = fetchCurrentMapLocation(context)
        if (latestLocation != null) {
            currentLocation = latestLocation
            onLocationUpdated(latestLocation)
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(latestLocation, 14.5f)
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(210.dp),
        shape    = RoundedCornerShape(20.dp),
        colors   = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = mapProperties,
                uiSettings = mapUiSettings
            ) {
                val markerPosition = currentLocation ?: hanoi
                Marker(
                    state = MarkerState(position = markerPosition),
                    title = if (currentLocation != null) "Vị trí của bạn" else "Hà Nội",
                    snippet = if (currentLocation != null) "Đã lấy từ GPS hiện tại" else "Khu vực trung tâm"
                )
            }

            // Overlay gradient để map hòa vào dark theme của app.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, DarkSurface.copy(alpha = 0.35f))
                        )
                    )
            )

            Icon(
                Icons.Default.Bookmark, null,
                tint     = PrimaryAmber,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp)
            )

            Button(
                onClick        = {},
                modifier       = Modifier.align(Alignment.BottomStart).padding(12.dp),
                shape          = RoundedCornerShape(24.dp),
                colors         = ButtonDefaults.buttonColors(containerColor = StatusRed),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ReportProblem, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Báo cáo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }

            Row(
                modifier              = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Icons.Default.Add to { scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomIn()) } },
                    Icons.Default.Remove to { scope.launch { cameraPositionState.animate(CameraUpdateFactory.zoomOut()) } }
                ).forEach { (icon, action) ->
                    Surface(shape = CircleShape, color = CardDark.copy(alpha = 0.9f), modifier = Modifier.size(38.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { action() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = TextWhite, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Surface(shape = CircleShape, color = CardDark.copy(alpha = 0.9f), modifier = Modifier.size(38.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                if (!context.hasMapLocationPermission()) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                    return@clickable
                                }

                                hasLocationPermission = true
                                scope.launch {
                                    val latestLocation = currentLocation ?: fetchCurrentMapLocation(context)
                                    val target = latestLocation ?: hanoi
                                    if (latestLocation != null) {
                                        currentLocation = latestLocation
                                        onLocationUpdated(latestLocation)
                                    }
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(target, 14.5f)
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            null,
                            tint = if (hasLocationPermission) PrimaryAmber else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun android.content.Context.hasMapLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private fun openGoogleMapsNavigation(context: Context, request: GoogleMapsLaunchRequest) {
    val mode = when (request.travelMode.trim().lowercase(Locale.US)) {
        "walking", "walk" -> "walking"
        "bicycling", "bike", "bicycle" -> "bicycling"
        "transit" -> "transit"
        else -> "driving"
    }

    val originForMaps = sanitizeOriginForGoogleMaps(request.origin)

    val uriBuilder = StringBuilder("https://www.google.com/maps/dir/?api=1")
    uriBuilder.append("&destination=").append(Uri.encode(request.destination))
    originForMaps
        ?.takeIf { it.isNotBlank() }
        ?.let { uriBuilder.append("&origin=").append(Uri.encode(it)) }
    uriBuilder.append("&travelmode=").append(Uri.encode(mode))

    val uri = Uri.parse(uriBuilder.toString())
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val pm = context.packageManager
    when {
        appIntent.resolveActivity(pm) != null -> context.startActivity(appIntent)
        webIntent.resolveActivity(pm) != null -> context.startActivity(webIntent)
        else -> Toast.makeText(context, "Khong mo duoc Google Maps tren thiet bi nay", Toast.LENGTH_SHORT).show()
    }
}

private fun sanitizeOriginForGoogleMaps(origin: String?): String? {
    val raw = origin?.trim().orEmpty()
    if (raw.isBlank()) return null

    val normalized = raw.lowercase(Locale.US)
    val hasLatLngLabels =
        normalized.contains("lat=") ||
            normalized.contains("lng=") ||
            normalized.contains("lon=") ||
            normalized.contains("longitude")
    if (hasLatLngLabels) {
        // Nếu origin là chuỗi tọa độ nội bộ thì bỏ origin để Maps dùng "Vị trí của bạn".
        return null
    }

    val coordinateOnlyPattern = Regex("^[-+]?\\d{1,2}(?:\\.\\d+)?\\s*,\\s*[-+]?\\d{1,3}(?:\\.\\d+)?$")
    if (coordinateOnlyPattern.matches(raw)) {
        return null
    }

    return raw
}

private suspend fun fetchCurrentMapLocation(context: android.content.Context): LatLng? {
    return try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val lastLocation = client.lastLocation.await()
        if (lastLocation != null) {
            LatLng(lastLocation.latitude, lastLocation.longitude)
        } else {
            val tokenSource = CancellationTokenSource()
            val current = client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token
            ).await()
            current?.let { LatLng(it.latitude, it.longitude) }
        }
    } catch (_: Exception) {
        null
    }
}

// ==============================
// Quick Action Card
// ==============================
@Composable
private fun QuickActionCard(
    icon    : ImageVector,
    title   : String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, null, tint = PrimaryAmber, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextWhite, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
