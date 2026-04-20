package com.scamshield.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scammers")
data class ScammerEntity(
    @PrimaryKey
    val phoneNumber: String,
    val lastMessage: String,
    val riskLevel: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val aiEnabled: Boolean = false
)
