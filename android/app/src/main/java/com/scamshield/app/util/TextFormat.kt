package com.scamshield.app.util

import java.util.Locale

/**
 * Replaces underscores with spaces and applies locale-aware title case to the first character
 * (replacement for deprecated [String.capitalize]).
 */
fun String.formatCategoryLabel(): String =
    replace("_", " ").replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
