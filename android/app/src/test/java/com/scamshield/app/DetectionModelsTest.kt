package com.scamshield.app

import com.scamshield.app.detection.model.MatchedRule
import com.scamshield.app.detection.model.RuleVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionModelsTest {
    @Test
    fun ruleVerdictHoldsData() {
        val verdict = RuleVerdict(
            isSuspicious = true,
            confidence = 0.75f,
            matchedRules = listOf(MatchedRule("keyword:urgent", 0.7f, "phishing")),
            categoryHints = listOf("phishing")
        )

        assertTrue(verdict.isSuspicious)
        assertEquals(0.75f, verdict.confidence)
        assertEquals("phishing", verdict.categoryHints.first())
    }
}
