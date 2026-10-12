package com.nendo.argosy.domain.upload

data class TransferConditions(
    val connected: Boolean,
    val unmetered: Boolean,
    val charging: Boolean
)

enum class TransferHold {
    NONE,
    OFFLINE,
    METERED_NETWORK,
    NOT_CHARGING;

    companion object {
        fun of(conditions: TransferConditions, wifiOnly: Boolean, chargingOnly: Boolean): TransferHold = when {
            !conditions.connected -> OFFLINE
            wifiOnly && !conditions.unmetered -> METERED_NETWORK
            chargingOnly && !conditions.charging -> NOT_CHARGING
            else -> NONE
        }
    }
}
