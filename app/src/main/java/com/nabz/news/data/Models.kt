package com.nabz.news.data

enum class NewsCategory(val label: String) { IRAN("ایران"), MIDDLE_EAST("خاورمیانه"), WORLD("جهان") }

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    val image: String?,
    val language: String,
    val published: Long,
    val category: NewsCategory = NewsCategory.WORLD,
    val importance: Int = 0,
    val sources: List<String> = emptyList()
)
