package com.example.utt_trafficjams.ui.screens.handbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.utt_trafficjams.data.model.HandbookFaqItem
import com.example.utt_trafficjams.ui.components.UTTTopBar
import com.example.utt_trafficjams.ui.theme.CardDark
import com.example.utt_trafficjams.ui.theme.CardDarkLight
import com.example.utt_trafficjams.ui.theme.DarkBackground
import com.example.utt_trafficjams.ui.theme.PrimaryAmber
import com.example.utt_trafficjams.ui.theme.ProTipEnd
import com.example.utt_trafficjams.ui.theme.ProTipStart
import com.example.utt_trafficjams.ui.theme.TextSecondary
import com.example.utt_trafficjams.ui.theme.TextTertiary
import com.example.utt_trafficjams.ui.theme.TextWhite

@Composable
fun HandbookScreen(vm: HandbookViewModel = viewModel()) {
    val searchQuery by vm.searchQuery.collectAsState()
    val faqItems by vm.faqItems.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
    ) {
        UTTTopBar(subtitle = null, showSettings = false)

        HandbookHeader()

        Spacer(modifier = Modifier.height(12.dp))

        SearchBar(
            query = searchQuery,
            onQueryChange = vm::updateSearchQuery
        )

        Spacer(modifier = Modifier.height(20.dp))

        PenaltyCategoryGrid()

        Spacer(modifier = Modifier.height(24.dp))

        FAQSection(
            faqItems = faqItems,
            searchQuery = searchQuery
        )

        Spacer(modifier = Modifier.height(20.dp))

        ProTipBanner()

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun HandbookHeader() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Cẩm nang pháp luật",
            style = MaterialTheme.typography.headlineLarge,
            color = TextWhite,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Tra cứu mức phạt và căn cứ pháp lý mới nhất",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
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
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Tìm hành vi vi phạm hoặc mức phạt...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextWhite),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PrimaryAmber
                )
            )
        }
    }
}

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
            title = "Mức phạt xe máy",
            subtitle = "Vi phạm phổ biến",
            modifier = Modifier.weight(1f)
        )
        PenaltyCategoryCard(
            icon = Icons.Default.DirectionsCar,
            title = "Mức phạt ô tô",
            subtitle = "Vi phạm phổ biến",
            modifier = Modifier.weight(1f)
        )
    }
}

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

@Composable
private fun FAQSection(
    faqItems: List<HandbookFaqItem>,
    searchQuery: String
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
            TextButton(onClick = { }) {
                Text(
                    text = "Xem tất cả",
                    style = MaterialTheme.typography.labelLarge,
                    color = PrimaryAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (faqItems.isEmpty()) {
            Text(
                text = if (searchQuery.isBlank()) {
                    "Chưa có dữ liệu cẩm nang để hiển thị."
                } else {
                    "Không tìm thấy mục phù hợp. Hãy thử từ khóa khác."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            faqItems.forEachIndexed { index, item ->
                FAQItem(item = item)
                if (index < faqItems.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FAQItem(item: HandbookFaqItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        onClick = { }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PrimaryAmber)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Căn cứ: ${item.legalReference}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.9f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

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
                Text(
                    text = "CẬP NHẬT CĂN CỨ PHÁP LÝ",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextWhite.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PrimaryAmber
                ) {
                    Text(
                        text = "LƯU Ý",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Ưu tiên đối chiếu hiệu lực văn bản trước khi áp mức phạt; khi cần, xem thêm điều/khoản tương ứng trong nghị định hiện hành.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextWhite.copy(alpha = 0.9f),
                    lineHeight = 22.sp
                )
            }
        }
    }
}
