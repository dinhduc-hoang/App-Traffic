package com.example.utt_trafficjams.ui.screens.handbook

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.utt_trafficjams.ui.components.UTTTopBar
import com.example.utt_trafficjams.ui.theme.*

// ==============================
// MÀN HÌNH CẨM NANG - Traffic Handbook
// Gồm: TopBar, Title, Search, PenaltyGrid,
//       FAQ Section, AI Search, ProTip Banner
// ==============================

@Composable
fun HandbookScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar
        UTTTopBar(subtitle = null, showSettings = false)

        // — Tiêu đề "Traffic Handbook"
        HandbookHeader()

        Spacer(modifier = Modifier.height(12.dp))

        // — Thanh tìm kiếm
        SearchBar()

        Spacer(modifier = Modifier.height(20.dp))

        // — Grid thể loại mức phạt (Xe máy, Ô tô)
        PenaltyCategoryGrid()

        Spacer(modifier = Modifier.height(24.dp))

        // — Section "Câu hỏi phổ biến" + danh sách FAQ
        FAQSection()

        Spacer(modifier = Modifier.height(24.dp))

        // — Card "Hỏi AI về luật giao thông"
        AISearchCard()

        Spacer(modifier = Modifier.height(20.dp))

        // — Banner "Pro Tip"
        ProTipBanner()

        Spacer(modifier = Modifier.height(100.dp))
    }
}

// ==============================
// Header: "Traffic Handbook" + subtitle
// ==============================
@Composable
private fun HandbookHeader() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Traffic Handbook",
            style = MaterialTheme.typography.headlineLarge,
            color = TextWhite,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Look Up Laws & Penalties",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

// ==============================
// Thanh tìm kiếm "Search for laws or penalties..."
// ==============================
@Composable
private fun SearchBar() {
    var searchQuery by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = CardDark
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (searchQuery.isEmpty()) {
                Text(
                    text = "Search for laws or penalties...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }
        }
    }
}

// ==============================
// Grid thể loại mức phạt (2 cột)
// "Mức phạt Xe máy" | "Mức phạt Ô tô"
// ==============================
@Composable
private fun PenaltyCategoryGrid() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PenaltyCategoryCard(
            icon = Icons.Default.TwoWheeler,
            title = "Mức phạt Xe máy",
            subtitle = "Motorbike Penalties",
            modifier = Modifier.weight(1f)
        )
        PenaltyCategoryCard(
            icon = Icons.Default.DirectionsCar,
            title = "Mức phạt Ô tô",
            subtitle = "Car Penalties",
            modifier = Modifier.weight(1f)
        )
    }
}

// ==============================
// Card thể loại mức phạt (reusable)
// Icon amber + title + subtitle
// ==============================
@Composable
private fun PenaltyCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon trong vòng tròn
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardDarkLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PrimaryAmber,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

// ==============================
// Section "Câu hỏi phổ biến"
// Header + 3 FAQ items
// ==============================
@Composable
private fun FAQSection() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Header: "Câu hỏi phổ biến" + "Xem tất cả"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Câu hỏi phổ biến",
                style = MaterialTheme.typography.headlineMedium,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { /* TODO */ }) {
                Text(
                    text = "Xem tất cả",
                    style = MaterialTheme.typography.labelLarge,
                    color = PrimaryAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // FAQ items
        FAQItem(question = "Vượt đèn đỏ phạt bao nhiêu?")
        Spacer(modifier = Modifier.height(8.dp))
        FAQItem(question = "Không đội mũ bảo hiểm?")
        Spacer(modifier = Modifier.height(8.dp))
        FAQItem(question = "Sử dụng điện thoại khi lái xe?")
    }
}

// ==============================
// Item câu hỏi FAQ (reusable)
// Viền vàng bên trái + text + chevron
// ==============================
@Composable
private fun FAQItem(question: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        onClick = { /* TODO */ }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thanh vàng bên trái (accent bar)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PrimaryAmber)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = question,
                style = MaterialTheme.typography.bodyLarge,
                color = TextWhite,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ==============================
// Card "Hỏi AI về luật giao thông"
// Icon sparkle + title + subtitle + input
// ==============================
@Composable
private fun AISearchCard() {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Sparkle icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PrimaryAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = PrimaryAmber,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Hỏi AI về luật giao thông",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Instant AI support for traffic regulations",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Input field "Hỏi AI..."
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = CardDarkLight
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hỏi AI...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier.weight(1f)
                    )

                    // Send button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PrimaryAmber),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Gửi",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==============================
// Banner "PRO TIP"
// Nền gradient xanh lam đậm + icon + text
// ==============================
@Composable
private fun ProTipBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(ProTipStart, ProTipEnd)
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Column {
                // "LUẬT THÔNG HÀNH GIO MỚI MỚI!!!" header
                Text(
                    text = "LUẬT GIAO THÔNG MỚI NHẤT!!!",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextWhite.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // PRO TIP label
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PrimaryAmber
                ) {
                    Text(
                        text = "PRO TIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tip content
                Text(
                    text = "Luôn mang theo giấy tờ xe bản gốc hoặc bản sao có chứng thực khi tham gia giao thông.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite.copy(alpha = 0.9f),
                    lineHeight = 22.sp
                )
            }
        }
    }
}
