package com.example.utt_trafficjams.ui.screens.routes

import android.app.Application
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.utt_trafficjams.data.model.RoutePlaceType
import com.example.utt_trafficjams.data.model.TrafficSchedule
import com.example.utt_trafficjams.data.repository.TrafficScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class RoutesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TrafficScheduleRepository(application)

    private val _schedules = MutableStateFlow<List<TrafficSchedule>>(emptyList())
    val schedules: StateFlow<List<TrafficSchedule>> = _schedules.asStateFlow()

    init {
        val data = repository.getSchedules()
        _schedules.value = data
    }

    fun addSchedule() {
        val now = Calendar.getInstance()
        val item = TrafficSchedule(
            id = UUID.randomUUID().toString(),
            actionName = "Lịch mới",
            placeType = RoutePlaceType.OTHER,
            destinationAddress = "",
            hour = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE)
        )
        persist(_schedules.value + item)
    }

    fun addScheduleConfigured(
        destinationName: String,
        placeType: RoutePlaceType,
        destinationAddress: String,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>
    ) {
        viewModelScope.launch {
            val normalizedName = destinationName.trim().ifEmpty { "Lịch mới" }
            val normalizedAddress = destinationAddress.trim()
            val resolvedPlaceType = if (placeType == RoutePlaceType.OTHER) {
                inferPlaceTypeFromText(normalizedName, normalizedAddress)
            } else {
                placeType
            }
            val normalizedDays = daysOfWeek.ifEmpty {
                setOf(
                    Calendar.MONDAY,
                    Calendar.TUESDAY,
                    Calendar.WEDNESDAY,
                    Calendar.THURSDAY,
                    Calendar.FRIDAY
                )
            }

            val resolvedLatLng = resolveDestinationLatLng(normalizedAddress)

            val item = TrafficSchedule(
                id = UUID.randomUUID().toString(),
                actionName = normalizedName,
                placeType = resolvedPlaceType,
                destinationAddress = normalizedAddress,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                daysOfWeek = normalizedDays,
                destinationLat = resolvedLatLng?.first ?: 21.0124,
                destinationLng = resolvedLatLng?.second ?: 105.8342
            )

            persist(_schedules.value + item)
        }
    }

    fun removeSchedule(id: String) {
        persist(_schedules.value.filterNot { it.id == id })
    }

    fun updateActionName(id: String, value: String) {
        val normalized = value.trim().ifEmpty { "Lịch trình" }
        persist(_schedules.value.map {
            if (it.id == id) {
                val inferredType = inferPlaceTypeFromText(normalized, it.destinationAddress)
                val finalType = if (it.placeType == RoutePlaceType.OTHER) inferredType else it.placeType
                it.copy(actionName = normalized, placeType = finalType)
            } else {
                it
            }
        })
    }

    fun updatePlaceType(id: String, value: RoutePlaceType) {
        persist(_schedules.value.map {
            if (it.id == id) it.copy(placeType = value) else it
        })
    }

    fun updateDestinationAddress(id: String, value: String) {
        val normalized = value.trim()
        persist(_schedules.value.map {
            if (it.id == id) it.copy(destinationAddress = normalized) else it
        })

        if (normalized.isBlank()) return

        viewModelScope.launch {
            val resolvedLatLng = resolveDestinationLatLng(normalized) ?: return@launch
            persist(_schedules.value.map {
                if (it.id == id) {
                    it.copy(destinationLat = resolvedLatLng.first, destinationLng = resolvedLatLng.second)
                } else {
                    it
                }
            })
        }
    }

    fun updateTime(id: String, hour: Int, minute: Int) {
        persist(_schedules.value.map {
            if (it.id == id) it.copy(hour = hour, minute = minute) else it
        })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        persist(_schedules.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        })
    }

    fun toggleDay(id: String, day: Int) {
        persist(_schedules.value.map { schedule ->
            if (schedule.id != id) return@map schedule

            val next = schedule.daysOfWeek.toMutableSet()
            if (day in next) next.remove(day) else next.add(day)
            schedule.copy(daysOfWeek = next)
        })
    }

    private fun persist(newData: List<TrafficSchedule>) {
        _schedules.value = newData
        repository.saveSchedules(newData)
    }

    private suspend fun resolveDestinationLatLng(address: String): Pair<Double, Double>? {
        if (address.isBlank()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                if (!Geocoder.isPresent()) return@runCatching null

                val geocoder = Geocoder(getApplication(), Locale("vi", "VN"))
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(address, 1)
                val first = results?.firstOrNull() ?: return@runCatching null
                Pair(first.latitude, first.longitude)
            }.getOrNull()
        }
    }

    private fun inferPlaceTypeFromText(name: String, address: String): RoutePlaceType {
        val content = (name + " " + address).lowercase(Locale.getDefault())
        return when {
            content.contains("nha") || content.contains("nhà") || content.contains("home") || content.contains("ve nha") || content.contains("về nhà") -> RoutePlaceType.HOME
            content.contains("co quan") ||
                content.contains("cơ quan") ||
                content.contains("cong ty") ||
                content.contains("công ty") ||
                content.contains("van phong") ||
                content.contains("văn phòng") ||
                content.contains("work") ||
                content.contains("truong") ||
                content.contains("trường") ||
                content.contains("di hoc") ||
                content.contains("đi học") ||
                content.contains("hoc") ||
                content.contains("học") -> RoutePlaceType.WORK
            else -> RoutePlaceType.OTHER
        }
    }
}
