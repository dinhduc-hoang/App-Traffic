package com.example.utt_trafficjams.ui.screens.routes

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.utt_trafficjams.data.model.RoutePlaceType
import com.example.utt_trafficjams.data.model.TrafficSchedule
import com.example.utt_trafficjams.ui.components.UTTTopBar
import com.example.utt_trafficjams.ui.theme.*
import java.util.Calendar
import java.util.Locale

@Composable
fun RoutesScreen(vm: RoutesViewModel = viewModel()) {
    val schedules by vm.schedules.collectAsState()
    var showAddRouteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
    ) {
        UTTTopBar(subtitle = null, showSettings = false)
        RoutesHeader(onAdd = { showAddRouteDialog = true })

        Spacer(modifier = Modifier.height(14.dp))

        schedules.forEachIndexed { idx, schedule ->
            ScheduleEditorCard(
                schedule = schedule,
                onChangeTime = { hour, minute -> vm.updateTime(schedule.id, hour, minute) },
                onToggleEnabled = { vm.setEnabled(schedule.id, it) },
                onToggleDay = { day -> vm.toggleDay(schedule.id, day) },
                onChangeName = { vm.updateActionName(schedule.id, it) },
                onChangePlaceType = { vm.updatePlaceType(schedule.id, it) },
                onChangeDestinationAddress = { vm.updateDestinationAddress(schedule.id, it) },
                onDelete = { vm.removeSchedule(schedule.id) },
                canDelete = schedules.size > 1,
                index = idx
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        AlertRuleCard()

        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showAddRouteDialog) {
        AddRouteDialog(
            onDismiss = { showAddRouteDialog = false },
            onConfirm = { name, placeType, address, hour, minute, days ->
                vm.addScheduleConfigured(
                    destinationName = name,
                    placeType = placeType,
                    destinationAddress = address,
                    hour = hour,
                    minute = minute,
                    daysOfWeek = days
                )
                showAddRouteDialog = false
            }
        )
    }
}

@Composable
private fun AddRouteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, RoutePlaceType, String, Int, Int, Set<Int>) -> Unit
) {
    val context = LocalContext.current
    val now = remember { Calendar.getInstance() }

    var destinationName by remember { mutableStateOf("") }
    var placeType by remember { mutableStateOf(RoutePlaceType.OTHER) }
    var destinationAddress by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(now.get(Calendar.MINUTE)) }
    var selectedDays by remember {
        mutableStateOf(
            setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY
            )
        )
    }

    val timeText = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(destinationName, placeType, destinationAddress, hour, minute, selectedDays)
                },
                enabled = destinationName.trim().isNotEmpty() && destinationAddress.trim().isNotEmpty()
            ) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        },
        title = {
            Text("Thêm lộ trình mới")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Loại địa điểm", fontWeight = FontWeight.Medium)
                PlaceTypeSelector(
                    selectedType = placeType,
                    onSelected = { placeType = it }
                )

                OutlinedTextField(
                    value = destinationName,
                    onValueChange = { destinationName = it },
                    label = { Text("Tên nơi đến") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = destinationAddress,
                    onValueChange = { destinationAddress = it },
                    label = { Text("Địa điểm đến") },
                    placeholder = { Text("VD: 54 Triều Khúc, Thanh Xuân") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Giờ đi: $timeText", fontWeight = FontWeight.Medium)
                    TextButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, newHour, newMinute ->
                                    hour = newHour
                                    minute = newMinute
                                },
                                hour,
                                minute,
                                true
                            ).show()
                        }
                    ) {
                        Text("Đổi giờ")
                    }
                }

                Text("Đi những thứ nào", fontWeight = FontWeight.Medium)
                RepeatDaysRow(
                    selectedDays = selectedDays,
                    onToggle = { day ->
                        selectedDays = selectedDays.toMutableSet().apply {
                            if (contains(day)) remove(day) else add(day)
                        }
                    }
                )
            }
        }
    )
}

@Composable
private fun RoutesHeader(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Lộ trình",
                style = MaterialTheme.typography.headlineLarge,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Đặt giờ xuất phát và quản lý lịch trình di chuyển",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Surface(
            shape = CircleShape,
            color = PrimaryAmber,
            modifier = Modifier.size(40.dp),
            onClick = onAdd
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Thêm lịch",
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun ScheduleEditorCard(
    schedule: TrafficSchedule,
    onChangeTime: (Int, Int) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleDay: (Int) -> Unit,
    onChangeName: (String) -> Unit,
    onChangePlaceType: (RoutePlaceType) -> Unit,
    onChangeDestinationAddress: (String) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    index: Int
) {
    val icon = when (schedule.placeType) {
        RoutePlaceType.HOME -> Icons.Default.Home
        RoutePlaceType.WORK -> Icons.Default.Work
        RoutePlaceType.OTHER -> when (index % 2) {
            0 -> Icons.Default.Route
            else -> Icons.Default.LocationOn
        }
    }
    var nameText by remember(schedule.id, schedule.actionName) {
        mutableStateOf(schedule.actionName)
    }
    var destinationAddressText by remember(schedule.id, schedule.destinationAddress) {
        mutableStateOf(schedule.destinationAddress)
    }
    val context = LocalContext.current
    val amPm = if (schedule.hour < 12) "AM" else "PM"
    val timeText = String.format(Locale.getDefault(), "%02d:%02d", schedule.hour, schedule.minute)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        onChangeName(it)
                    },
                    label = { Text("Tên nơi đến", color = TextSecondary) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryAmber,
                        unfocusedBorderColor = CardDarkLighter,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        cursorColor = PrimaryAmber
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PrimaryAmber,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Loại địa điểm", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(6.dp))
            PlaceTypeSelector(
                selectedType = schedule.placeType,
                onSelected = onChangePlaceType
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = destinationAddressText,
                onValueChange = {
                    destinationAddressText = it
                    onChangeDestinationAddress(it)
                },
                label = { Text("Địa điểm đến", color = TextSecondary) },
                placeholder = { Text("VD: 54 Triều Khúc, Thanh Xuân", color = TextTertiary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryAmber,
                    unfocusedBorderColor = CardDarkLighter,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    cursorColor = PrimaryAmber
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.displayLarge,
                        color = PrimaryAmber,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = amPm,
                        style = MaterialTheme.typography.titleLarge,
                        color = PrimaryAmber.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = PrimaryAmber,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = CardDarkLighter
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> onChangeTime(hour, minute) },
                            schedule.hour,
                            schedule.minute,
                            true
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CardDarkLight),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.AccessTime, null, tint = PrimaryAmber)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Đổi giờ", color = TextWhite)
                }

                if (canDelete) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1E1E)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFFB7B7))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Xóa", color = Color(0xFFFFDADA))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Lặp lại",
                style = MaterialTheme.typography.titleMedium,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            RepeatDaysRow(
                selectedDays = schedule.daysOfWeek,
                onToggle = onToggleDay
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (schedule.destinationAddress.isBlank()) {
                    "Lịch trình theo giờ đã đặt. Bạn nên thêm địa điểm đến để quản lý rõ ràng hơn."
                } else {
                    "Điểm đến: ${schedule.destinationAddress}. Lịch trình theo giờ đã đặt."
                },
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary)

        }
    }
}

@Composable
private fun PlaceTypeSelector(
    selectedType: RoutePlaceType,
    onSelected: (RoutePlaceType) -> Unit
) {
    val options = listOf(
        RoutePlaceType.HOME to "Nhà",
        RoutePlaceType.WORK to "Cơ quan",
        RoutePlaceType.OTHER to "Khác"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (type, label) ->
            val selected = selectedType == type
            FilterChip(
                selected = selected,
                onClick = { onSelected(type) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryAmber,
                    selectedLabelColor = Color.Black,
                    containerColor = CardDarkLight,
                    labelColor = TextWhite
                )
            )
        }
    }
}

@Composable
private fun RepeatDaysRow(
    selectedDays: Set<Int>,
    onToggle: (Int) -> Unit
) {
    val days = listOf(
        Calendar.MONDAY to "T2",
        Calendar.TUESDAY to "T3",
        Calendar.WEDNESDAY to "T4",
        Calendar.THURSDAY to "T5",
        Calendar.FRIDAY to "T6",
        Calendar.SATURDAY to "T7",
        Calendar.SUNDAY to "CN"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        days.forEach { (dayValue, dayLabel) ->
            val isSelected = dayValue in selectedDays
            DayChip(
                day = dayLabel,
                isSelected = isSelected,
                onClick = { onToggle(dayValue) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ==============================
// Chip ngày (reusable)
// Selected = nền amber + text đen
// Unselected = viền xám + text xám
// ==============================
@Composable
private fun DayChip(
    day: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .then(
                if (!isSelected) Modifier.border(1.dp, CardDarkLighter, CircleShape)
                else Modifier
            ),
        shape = CircleShape,
        color = if (isSelected) PrimaryAmber else Color.Transparent,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = day,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.Black else TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun AlertRuleCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Route, null, tint = PrimaryAmber)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Thông tin lộ trình", color = TextWhite, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Lộ trình lưu thông tin điểm đến, giờ đi và ngày đi lặp lại.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tính năng cảnh báo tắc đường trước giờ đi 30 phút đang được bật.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

