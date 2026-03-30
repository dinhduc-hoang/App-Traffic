package com.example.utt_trafficjams.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.utt_trafficjams.ui.theme.*

// ==============================
// Bottom Navigation Bar
// 3 tab: Home, Lộ trình, Cẩm nang
// ==============================

data class BottomNavItem(
    val label: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem("Home", "home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem("Lộ trình", "routes", Icons.Filled.DirectionsBus, Icons.Outlined.DirectionsBus),
    BottomNavItem("Cẩm nang", "handbook", Icons.Filled.MenuBook, Icons.Outlined.MenuBook)
)

@Composable
fun UTTBottomNavigation(
    currentRoute: String,
    onItemClick: (String) -> Unit
) {
    NavigationBar(
        containerColor = NavBarBg,
        contentColor = TextWhite,
        tonalElevation = 0.dp,
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = currentRoute == item.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                selected = isSelected,
                onClick = { onItemClick(item.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NavItemActive,
                    selectedTextColor = NavItemActive,
                    unselectedIconColor = NavItemInactive,
                    unselectedTextColor = NavItemInactive,
                    indicatorColor = PrimaryAmber.copy(alpha = 0.15f)
                )
            )
        }
    }
}
