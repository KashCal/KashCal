package org.onekash.kashcal.domain.quickadd.tokenizer

enum class TokenType {
    NUMBER,
    MONTH,
    YEAR,
    WEEKDAY,
    TIME,
    TIME_RANGE,
    STRUCTURED_DATE,
    UNIT,
    MERIDIEM,
    KEYWORD,
    DATE_KEYWORD,
    TIME_KEYWORD,
    RECURRENCE_KEYWORD,
    TIMEZONE,
    UNKNOWN
}
