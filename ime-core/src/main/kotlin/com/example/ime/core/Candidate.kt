package com.example.ime.core

data class Candidate(
    val text: String,
    val pinyin: String,
    val weight: Int,
)
