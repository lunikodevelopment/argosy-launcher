package com.nendo.argosy.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferHoldTest {

    private fun hold(connected: Boolean, unmetered: Boolean, charging: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) =
        TransferHold.of(TransferConditions(connected, unmetered, charging), wifiOnly, chargingOnly)

    @Test
    fun `offline always holds`() {
        assertEquals(TransferHold.OFFLINE, hold(false, false, true, wifiOnly = false, chargingOnly = false))
    }

    @Test
    fun `wifi only holds on a metered network`() {
        assertEquals(TransferHold.METERED_NETWORK, hold(true, false, true, wifiOnly = true, chargingOnly = false))
        assertEquals(TransferHold.NONE, hold(true, false, true, wifiOnly = false, chargingOnly = false))
    }

    @Test
    fun `charging only holds on battery`() {
        assertEquals(TransferHold.NOT_CHARGING, hold(true, true, false, wifiOnly = false, chargingOnly = true))
        assertEquals(TransferHold.NONE, hold(true, true, true, wifiOnly = true, chargingOnly = true))
    }
}
