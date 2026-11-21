package com.fieldcam.timestamp

/**
 * 61 selectable timestamp patterns (java.text.SimpleDateFormat syntax).
 * Patterns containing "SSS" render milliseconds (0.001 s), and the overlay is
 * redrawn on every camera frame, so recorded video shows a running ms clock.
 */
object TimeFormats {
    val PATTERNS: List<String> = listOf(
        // 1-10: the default style seen on field photos
        "MMM d, yyyy h:mm:ss a",
        "MMM d, yyyy h:mm:ss.SSS a",
        "MMM d, yyyy HH:mm:ss",
        "MMM d, yyyy HH:mm:ss.SSS",
        "MMMM d, yyyy h:mm:ss a",
        "MMMM d, yyyy HH:mm:ss.SSS",
        "EEE, MMM d, yyyy h:mm:ss a",
        "EEE, MMM d, yyyy HH:mm:ss.SSS",
        "EEEE, MMMM d, yyyy h:mm a",
        "EEEE, MMMM d, yyyy HH:mm:ss",
        // 11-20: ISO-like
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd hh:mm:ss a",
        "yyyy-MM-dd hh:mm:ss.SSS a",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss.SSS z",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        // 21-30: slashes, US
        "MM/dd/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm:ss.SSS",
        "MM/dd/yyyy hh:mm:ss a",
        "MM/dd/yyyy hh:mm:ss.SSS a",
        "MM/dd/yy HH:mm:ss",
        "MM/dd/yy hh:mm a",
        "M/d/yyyy h:mm:ss a",
        "M/d/yyyy H:mm:ss.SSS",
        "MM/dd/yyyy",
        "MM/dd/yyyy EEE HH:mm:ss",
        // 31-40: slashes, EU
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss.SSS",
        "dd/MM/yyyy hh:mm:ss a",
        "dd/MM/yy HH:mm:ss",
        "d/M/yyyy H:mm:ss",
        "dd/MM/yyyy",
        "dd/MM/yyyy EEE HH:mm:ss",
        "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm:ss.SSS",
        "dd.MM.yy HH:mm",
        // 41-50: dashes, day-first, text months
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm:ss.SSS",
        "dd-MMM-yyyy HH:mm:ss",
        "dd-MMM-yyyy hh:mm:ss a",
        "d MMM yyyy HH:mm:ss",
        "d MMM yyyy HH:mm:ss.SSS",
        "d MMMM yyyy, HH:mm",
        "EEE d MMM yyyy HH:mm:ss",
        "EEEE d MMMM yyyy HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        // 51-61: compact, CJK, time-only, technical
        "yyyy/MM/dd HH:mm:ss.SSS",
        "yyyy.MM.dd HH:mm:ss",
        "yyyy.MM.dd HH:mm:ss.SSS",
        "yyyyMMdd HHmmss",
        "yyyyMMdd_HHmmss.SSS",
        "yyyy'年'MM'月'dd'日' HH:mm:ss",
        "yyyy'년' MM'월' dd'일' HH:mm:ss",
        "HH:mm:ss",
        "HH:mm:ss.SSS",
        "hh:mm:ss a",
        "yyyy-DDD HH:mm:ss.SSS (w'th week')"
    )

    init {
        check(PATTERNS.size == 61)
    }
}
