package com.shilapi.xcertplay.network

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wired AirPlay listener must bind fe80::2 with the tun's scope; Android 7 cannot find it (#557). */
class CarPlayVpnLinkLocalTest {
    private val linkLocal = InetAddress.getByName("fe80::2") as Inet6Address

    @Test fun unscopedLinkLocalGetsTheScopeOfTheInterfaceThatHasIt() {
        val scoped = CarPlayVpnService.scopedLinkLocal(linkLocal) { if (it == linkLocal) 12 else null }

        assertEquals(12, scoped.scopeId)
        assertArrayEquals(linkLocal.address, scoped.address)
        assertTrue(scoped.isLinkLocalAddress)
    }

    @Test fun scopedAndGlobalAddressesAreKeptWithoutALookup() {
        val alreadyScoped = Inet6Address.getByAddress(null, linkLocal.address, 5)
        val global = InetAddress.getByName("2001:db8::2") as Inet6Address
        val noLookup: (Inet6Address) -> Int? = { throw AssertionError("unexpected interface lookup") }

        assertSame(alreadyScoped, CarPlayVpnService.scopedLinkLocal(alreadyScoped, noLookup))
        assertSame(global, CarPlayVpnService.scopedLinkLocal(global, noLookup))
    }

    @Test fun missingInterfaceFailsWithTheAddressInsteadOfInvalidArgument() {
        for (index in listOf(null, 0)) {
            val error = assertThrows(IOException::class.java) {
                CarPlayVpnService.scopedLinkLocal(linkLocal) { index }
            }
            assertTrue(error.message, error.message!!.startsWith("No network interface has fe80:"))
        }
    }
}
