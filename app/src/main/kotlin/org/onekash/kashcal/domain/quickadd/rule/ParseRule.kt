package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token

fun interface ParseRule {
    fun apply(tokens: List<Token>, context: ParseContext)
}
