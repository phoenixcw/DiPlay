package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.net.BindException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * On-device check for #557. Grant VPN consent first:
 * `adb shell appops set com.shilapi.xcertplay.shared.test ACTIVATE_VPN allow`
 */
class WiredLinkLocalBindDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** What the platform does with an fe80::2 bind on a VPN tun, without and with a scope. */
    @Test fun platformBindOfTunLinkLocal() = withService { service ->
        assertNull("VPN consent missing", VpnService.prepare(context))
        val tun = service.Builder()
            .addAddress("fe80::2", 64)
            .addRoute("fe80::", 64)
            .setMtu(1500)
            .addAllowedApplication(context.packageName)
            .setSession("link-local bind test")
            .establish()
        assertNotNull("VpnService.establish returned null", tun)
        tun!!.use {
            val unscoped = InetAddress.getByName("fe80::2") as Inet6Address
            val unscopedBind = runCatching { ServerSocket().use { socket -> socket.bind(InetSocketAddress(unscoped, 0)) } }
            val index = NetworkInterface.getByInetAddress(unscoped)?.index
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} unscoped bind=" +
                "${unscopedBind.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" } ?: "bound"} tunIndex=$index")
            assertNotNull("No interface has fe80::2", index)
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(Inet6Address.getByAddress(null, unscoped.address, index!!), 0))
                assertTrue(socket.isBound)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val error = unscopedBind.exceptionOrNull()
                assertTrue("Expected a SocketException, got $error", error is SocketException && error !is BindException)
                assertEquals("Invalid argument", error!!.message)
            } else {
                assertTrue("Android 8+ resolves the scope itself: ${unscopedBind.exceptionOrNull()}", unscopedBind.isSuccess)
            }
        }
    }

    /** The production wired attach; #557 reports it failing with "Invalid argument" on Android 7.1. */
    @Test fun wiredAttachStartsTheAirPlayListener() = withService { service ->
        assertNull("VPN consent missing", VpnService.prepare(context))
        val ncm = try {
            fakeNcm()
        } catch (error: ReflectiveOperationException) {
            assumeNoException("Hidden USB constructors are unavailable", error)
            return@withService
        }
        val result = service.attach(ncm, "fe80::2", ByteArray(6), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        Log.i(TAG, "API ${Build.VERSION.SDK_INT} attach=$result detail=${service.lastAttachFailure}")
        try {
            // The fake NCM link fails right after the listener starts; only the attach result counts.
            assertEquals("detail=${service.lastAttachFailure}", CarPlayVpnService.AttachResult.Started, result)
        } finally {
            service.detach()
        }
    }

    private fun withService(check: (CarPlayVpnService) -> Unit) {
        val bound = CountDownLatch(1)
        var service: CarPlayVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as CarPlayVpnService.LocalBinder).service
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, CarPlayVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("CarPlayVpnService did not bind", bound.await(10, TimeUnit.SECONDS))
            check(service!!)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun fakeNcm(): NcmUsbBridge {
        val connection = UsbDeviceConnection::class.java.getDeclaredConstructor(UsbDevice::class.java)
            .apply { isAccessible = true }.newInstance(null)
        fun endpoint(address: Int): UsbEndpoint = UsbEndpoint::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(address, 2, 512, 0)
        return NcmUsbBridge(connection, endpoint(0x04), endpoint(0x85), null, emptyList(), null)
    }

    private val config = AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02",
        "1", AirPlayDisplayConfig(800, 480), port = 0)
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")

    private companion object {
        const val TAG = "LinkLocalBindTest"
    }
}
