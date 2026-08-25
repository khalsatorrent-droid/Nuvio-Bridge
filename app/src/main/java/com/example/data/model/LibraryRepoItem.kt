package com.example.data.model

data class LibraryRepoItem(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val manifestUrl: String,
    val homepageUrl: String = "https://nuvio-plugin-library.vercel.app/",
    val tags: List<String> = listOf("Movies", "Series", "4K"),
    val isVerified: Boolean = true,
    val estimatedProviders: Int = 5,
    val badge: String = "Popular"
)
