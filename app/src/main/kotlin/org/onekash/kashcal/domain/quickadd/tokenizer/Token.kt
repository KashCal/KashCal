package org.onekash.kashcal.domain.quickadd.tokenizer

data class Token(
    val type: TokenType,
    val text: String,
    val value: Any? = null,
    val originalText: String = text
)
