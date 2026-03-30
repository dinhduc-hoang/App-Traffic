package com.example.utt_trafficjams.data.repository

import com.example.utt_trafficjams.data.model.HandbookFaqItem
import java.text.Normalizer
import java.util.Locale

class HandbookRepository {

    private val faqItems = listOf(
        HandbookFaqItem(
            question = "Vượt đèn đỏ phạt bao nhiêu?",
            legalReference = "Nghị định 100/2019/NĐ-CP (sửa đổi bởi Nghị định 123/2021/NĐ-CP)",
            summary = "Mức phạt phụ thuộc loại xe và tình tiết vi phạm; cần đối chiếu điều khoản hiện hành."
        ),
        HandbookFaqItem(
            question = "Không đội mũ bảo hiểm bị xử phạt như thế nào?",
            legalReference = "Nghị định 100/2019/NĐ-CP",
            summary = "Hành vi không đội mũ bảo hiểm hoặc đội không đúng quy cách đều có thể bị xử phạt."
        ),
        HandbookFaqItem(
            question = "Sử dụng điện thoại khi lái xe bị phạt ra sao?",
            legalReference = "Nghị định 100/2019/NĐ-CP",
            summary = "Mức phạt tăng theo loại phương tiện; có thể kèm hình thức xử phạt bổ sung."
        ),
        HandbookFaqItem(
            question = "Đi sai làn đường có bị tước GPLX không?",
            legalReference = "Nghị định 100/2019/NĐ-CP",
            summary = "Một số trường hợp đi sai làn có thể kèm hình thức tước quyền sử dụng GPLX."
        ),
        HandbookFaqItem(
            question = "Nồng độ cồn vượt mức quy định bị xử lý thế nào?",
            legalReference = "Nghị định 100/2019/NĐ-CP",
            summary = "Mức xử phạt nồng độ cồn phân theo ngưỡng vi phạm và loại phương tiện điều khiển."
        )
    )

    fun getFaqItems(): List<HandbookFaqItem> = faqItems

    fun searchFaq(query: String): List<HandbookFaqItem> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return faqItems

        return faqItems.filter { item ->
            normalize(item.question).contains(normalizedQuery) ||
                normalize(item.legalReference).contains(normalizedQuery) ||
                normalize(item.summary).contains(normalizedQuery)
        }
    }

    private fun normalize(input: String): String {
        val lowered = input.lowercase(Locale.getDefault())
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }
}
