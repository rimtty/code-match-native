package jp.rimtty.codematch.scanner.inateck

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit hardware gate for the official Inateck SDK.
 *
 * Run with `-e inateckPhysical true` while one supported scanner is awake and
 * available. The assertions inspect only response shape and known setting
 * names; scanner identifiers, raw frames, and setting values are never logged.
 */
@RunWith(AndroidJUnit4::class)
class InateckPhysicalConnectionTest {
    private lateinit var gateway: AndroidInateckSdkGateway
    private var connectedDeviceId: String? = null

    @Before
    fun requireExplicitHardwareRun() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("inateckPhysical") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(
            targetPackage,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            targetPackage,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        gateway = AndroidInateckSdkGateway(
            instrumentation.targetContext,
        )
    }

    @After
    fun disconnectAndClose() {
        if (!::gateway.isInitialized) return
        connectedDeviceId?.let { deviceId ->
            val disconnected = CountDownLatch(1)
            gateway.disconnect(deviceId) { disconnected.countDown() }
            disconnected.await(DISCONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        gateway.close()
    }

    @Test
    fun connectionSettingsInventoryAndDisconnectFollowSdkLifecycle() {
        val discovered = AtomicReference<InateckSdkDevice?>()
        val discoveryFinished = CountDownLatch(1)
        assertTrue(
            gateway.startDiscovery(
                onDevice = { device -> discovered.compareAndSet(null, device) },
                onFinished = discoveryFinished::countDown,
            ),
        )
        assertTrue(discoveryFinished.await(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val device = discovered.get() ?: bondedScanner()
        assertNotNull("The SDK did not discover a supported scanner", device)

        val connectionResult = AtomicReference<Result<Unit>?>()
        val connectionFinished = CountDownLatch(1)
        assertTrue(
            gateway.connect(
                deviceId = requireNotNull(device).id,
                onScanBytes = {},
                onDisconnected = {},
            ) { result ->
                connectionResult.set(result)
                connectionFinished.countDown()
            },
        )
        assertTrue(connectionFinished.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(
            "Official SDK connection setup failed at: " +
                connectionResult.get()?.exceptionOrNull()?.message.orEmpty(),
            connectionResult.get()?.isSuccess == true,
        )
        connectedDeviceId = device.id
        val inventoryResult = AtomicReference<Result<List<Map<String, String>>>?>()
        val inventoryFinished = CountDownLatch(1)
        assertTrue(
            gateway.readSettings(device.id) { result ->
                inventoryResult.set(result)
                inventoryFinished.countDown()
            },
        )
        assertTrue(inventoryFinished.await(SETTINGS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val inventory = inventoryResult.get()?.getOrNull()
        assertNotNull(
            "Official SDK settings read failed at: " +
                inventoryResult.get()?.exceptionOrNull()?.message.orEmpty(),
            inventory,
        )
        requireNotNull(inventory)
        assertTrue("Official SDK returned an empty settings inventory", inventory.isNotEmpty())

        val keyShapes = inventory.map { it.keys }.toSet()
        assertEquals(
            "BCST settings inventory uses an unexpected key shape: $keyShapes",
            setOf(setOf("area", "name", "value")),
            keyShapes,
        )
        val settingNames = inventory.mapNotNull { it["name"] }.map(String::lowercase).toSet()
        assertTrue("QR setting is absent from the SDK inventory", "qrcode_on" in settingNames)
        assertTrue("Code 128 setting is absent from the SDK inventory", "code128_on" in settingNames)

        val disconnected = CountDownLatch(1)
        val disconnectResult = AtomicReference<Result<Unit>?>()
        assertTrue(
            gateway.disconnect(device.id) { result ->
                disconnectResult.set(result)
                disconnected.countDown()
            },
        )
        assertTrue(disconnected.await(DISCONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("Official SDK disconnect failed", disconnectResult.get()?.isSuccess == true)
        connectedDeviceId = null
    }

    @Test
    fun physicalScanReachesTheOfficialNotificationParser() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("inateckScanPhysical") == "true",
        )
        val observedKinds = CopyOnWriteArrayList<InateckNotificationKind>()
        gateway.close()
        gateway = AndroidInateckSdkGateway(
            InstrumentationRegistry.getInstrumentation().targetContext,
            notificationObserver = observedKinds::add,
        )
        val device = discoverScanner()

        val connectionResult = AtomicReference<Result<Unit>?>()
        val connectionFinished = CountDownLatch(1)
        val scanFinished = CountDownLatch(1)
        assertTrue(
            gateway.connect(
                deviceId = device.id,
                onScanBytes = { scanFinished.countDown() },
                onDisconnected = {},
            ) { result ->
                connectionResult.set(result)
                connectionFinished.countDown()
            },
        )
        assertTrue(connectionFinished.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(
            "Official SDK connection setup failed at: " +
                connectionResult.get()?.exceptionOrNull()?.message.orEmpty(),
            connectionResult.get()?.isSuccess == true,
        )
        connectedDeviceId = device.id
        // The write-only SDK-output command can leave one control response on
        // FF01 during connection setup. Observe only notifications received
        // after the gateway has declared the link ready for scanning.
        observedKinds.clear()
        val original = readSettings(device.id)
        val symbologies = InateckAreaNameSettingsContract.extractSymbologies(original)
        assertNotNull("The SDK settings inventory could not be normalized", symbologies)
        val originalCommand = Gson().toJson(requireNotNull(symbologies))
        val restrictedCommand = Gson().toJson(
            symbologies.map { setting ->
                setting.copy(
                    value = if (setting.name.lowercase() in REQUIRED_SCAN_SETTINGS) "1" else "0",
                )
            },
        )

        writeSettings(device.id, restrictedCommand, "restricted scan settings")
        try {
            observedKinds.clear()
            assertTrue(
                "No scan reached the app. Payload-free parser outcomes: $observedKinds",
                scanFinished.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue(
                "The official parser did not classify the notification as a scan: $observedKinds",
                InateckNotificationKind.SCAN in observedKinds,
            )
        } finally {
            writeSettings(device.id, originalCommand, "original scan settings")
        }
    }

    private fun readSettings(deviceId: String): List<Map<String, String>> {
        val result = AtomicReference<Result<List<Map<String, String>>>?>()
        val finished = CountDownLatch(1)
        assertTrue(gateway.readSettings(deviceId) { value ->
            result.set(value)
            finished.countDown()
        })
        assertTrue(finished.await(SETTINGS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val inventory = result.get()?.getOrNull()
        assertNotNull("Official SDK settings read failed", inventory)
        return requireNotNull(inventory)
    }

    private fun writeSettings(deviceId: String, command: String, stage: String) {
        val result = AtomicReference<Result<Unit>?>()
        val finished = CountDownLatch(1)
        assertTrue(gateway.writeSettings(deviceId, command) { value ->
            result.set(value)
            finished.countDown()
        })
        assertTrue(
            "$stage did not finish",
            finished.await(SETTINGS_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertTrue("$stage failed", result.get()?.isSuccess == true)
    }

    private fun discoverScanner(): InateckSdkDevice {
        val discovered = AtomicReference<InateckSdkDevice?>()
        repeat(DISCOVERY_ATTEMPTS) {
            val discoveryFinished = CountDownLatch(1)
            assertTrue(
                gateway.startDiscovery(
                    onDevice = { device -> discovered.compareAndSet(null, device) },
                    onFinished = discoveryFinished::countDown,
                ),
            )
            assertTrue(discoveryFinished.await(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            discovered.get()?.let { return it }
        }
        val device = bondedScanner()
        assertNotNull("The SDK did not discover a supported scanner", device)
        return requireNotNull(device)
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_SECONDS = 10L
        const val DISCOVERY_ATTEMPTS = 3
        const val CONNECTION_TIMEOUT_SECONDS = 40L
        const val SETTINGS_TIMEOUT_SECONDS = 10L
        const val SETTINGS_WRITE_TIMEOUT_SECONDS = 20L
        const val SCAN_TIMEOUT_SECONDS = 45L
        const val DISCONNECT_TIMEOUT_SECONDS = 10L
        val REQUIRED_SCAN_SETTINGS = setOf("qrcode_on", "code128_on")
    }

    private fun bondedScanner(): InateckSdkDevice? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return adapter.bondedDevices
            .asSequence()
            .mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                if (!name.startsWith("HPRT-") && !name.startsWith("BCST-")) {
                    return@mapNotNull null
                }
                InateckSdkDevice(id = device.address, name = name)
            }
            .singleOrNull()
    }
}
