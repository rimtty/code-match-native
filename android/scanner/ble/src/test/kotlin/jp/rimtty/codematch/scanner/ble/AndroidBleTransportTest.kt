package jp.rimtty.codematch.scanner.ble

import java.util.UUID
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exercise the Android transport through its framework seam. No
 * Bluetooth adapter, scanner, Android permission dialog, or vendor SDK is
 * needed to verify the platform lifetime and callback rules.
 */
class AndroidBleTransportTest {
    private val serviceUuid = UUID.fromString("12345678-1234-5678-1234-567812345678")
    private val readUuid = UUID.fromString("12345678-1234-5678-1234-567812345679")
    private val writeUuid = UUID.fromString("12345678-1234-5678-1234-567812345680")
    private val notifyUuid = UUID.fromString("12345678-1234-5678-1234-567812345681")
    private val device = ScannerDevice("AA:BB:CC:DD:EE:FF", "test-scanner")

    @Test
    fun api31PermissionsAreCheckedBeforePlatformOperations() {
        val platform = FakePlatform()
        val permissions = FakePermissions(
            discovery = BlePermissionState.DENIED,
            connection = BlePermissionState.DENIED,
        )
        val transport = newTransport(platform = platform, permissions = permissions)

        assertEquals(BlePermissionState.DENIED, transport.readiness.discoveryPermission)
        assertEquals(BlePermissionState.DENIED, transport.readiness.connectionPermission)
        assertFalse(transport.startDiscovery())
        assertFalse(transport.connect(device))
        assertEquals(0, platform.startDiscoveryCalls)
        assertEquals(0, platform.connectCalls)

        permissions.discovery = BlePermissionState.GRANTED
        assertTrue(transport.startDiscovery())
        assertEquals(1, platform.startDiscoveryCalls)
    }

    @Test
    fun discoveryStopsAtFiveSecondsAndLateScanCallbackIsIgnored() {
        val platform = FakePlatform()
        val scheduler = FakeScheduler()
        val transport = newTransport(platform = platform, scheduler = scheduler)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = BleTransportListener { events += it }

        assertTrue(transport.startDiscovery())
        val firstCallback = platform.scanCallbacks.single()
        scheduler.runNext(5_000L)

        assertEquals(1, platform.stopDiscoveryCalls)
        assertTrue(events.contains(BleTransportEvent.DiscoveryStopped))
        firstCallback.onScanResult("late", "late", setOf(serviceUuid.toString()))
        assertTrue(events.none { it is BleTransportEvent.DeviceFound })
    }

    @Test
    fun connectionStopsAtThirtySecondsWithDisconnectCloseAndFailureEvent() {
        val platform = FakePlatform()
        val scheduler = FakeScheduler()
        val transport = newTransport(platform = platform, scheduler = scheduler)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = BleTransportListener { events += it }

        assertTrue(transport.connect(device))
        val gatt = platform.gatts.single()
        scheduler.runNext(30_000L)

        assertEquals(1, gatt.disconnectCalls)
        assertEquals(1, gatt.closeCalls)
        val failed = events.filterIsInstance<BleTransportEvent.ConnectionFailed>().single()
        assertEquals("Bluetooth connection timed out", failed.reason)
        assertEquals(1L, failed.linkGeneration)
        assertEquals(1L, failed.requestGeneration)
    }

    @Test
    fun lifecycleStopDisconnectsClosesResetsAndDropsOldGenerationCallbacks() {
        val platform = FakePlatform()
        val transport = newTransport(platform = platform)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = BleTransportListener { events += it }

        assertTrue(transport.connect(device))
        val firstCallback = platform.gattCallbacks.single()
        val firstGatt = platform.gatts.single()
        platform.emitConnected(0, firstGatt)
        platform.emitServices(0, firstGatt)
        assertTrue(events.any { it is BleTransportEvent.Connected })

        transport.setLifecycleState(BleAdapterLifecycleState.BACKGROUND)
        assertEquals(1, firstGatt.disconnectCalls)
        assertEquals(1, firstGatt.closeCalls)
        assertEquals(BleAdapterLifecycleState.BACKGROUND, transport.readiness.lifecycle)
        assertTrue(events.last() is BleTransportEvent.Disconnected)

        transport.setLifecycleState(BleAdapterLifecycleState.FOREGROUND)
        assertTrue(transport.connect(device))
        val secondGatt = platform.gatts.last()
        firstCallback.onConnectionStateChanged(firstGatt, 0, true)
        assertEquals(1, events.count { it is BleTransportEvent.Connected })
        assertTrue(firstGatt.closeCalls >= 2)
        assertSame(secondGatt, platform.gatts.last())
    }

    @Test
    fun synchronousReadAndWriteFailureCompleteExactlyOnce() {
        val platform = FakePlatform()
        val transport = newTransport(platform = platform)
        connectAndDiscover(transport, platform)
        val gatt = platform.gatts.single()
        gatt.readAccepted = false
        gatt.writeAccepted = false
        var readCompletions = 0
        var writeCompletions = 0

        assertFalse(transport.read(readUuid.toString()) {
            readCompletions++
            assertTrue(it.isFailure)
        })
        assertFalse(transport.write(writeUuid.toString(), byteArrayOf(1, 2, 3)) {
            writeCompletions++
            assertTrue(it.isFailure)
        })

        assertEquals(1, readCompletions)
        assertEquals(1, writeCompletions)
        // A late callback after synchronous rejection cannot complete again.
        platform.emitRead(0, gatt, readUuid, 0, byteArrayOf(9))
        platform.emitWrite(0, gatt, writeUuid, 0)
        assertEquals(1, readCompletions)
        assertEquals(1, writeCompletions)
    }

    @Test
    fun profileControlsEndpointsWriteTypeAndNotificationFramingWithoutLoggingRawValue() {
        val platform = FakePlatform()
        val rawPayload = byteArrayOf(0x01, 0x02, 0x03)
        val privateValue = "PRIVATE-SCAN-PAYLOAD"
        val profile = AndroidBleProtocolProfile(
            serviceUuid = serviceUuid,
            readCharacteristicUuid = readUuid,
            writeCharacteristicUuid = writeUuid,
            notifyCharacteristicUuid = notifyUuid,
            writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            notificationDecoder = BleNotificationDecoder {
                assertTrue(rawPayload.contentEquals(it))
                BleDecodedNotification(privateValue, ScanFormat.QR)
            },
        )
        val transport = AndroidBleTransport(
            profile = profile,
            scheduler = FakeScheduler(),
            permissionChecker = FakePermissions(),
            platform = platform,
        )
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = BleTransportListener { events += it }
        connectAndDiscover(transport, platform)

        assertTrue(transport.write(writeUuid.toString(), byteArrayOf(8, 7, 6)) { })
        assertEquals(
            android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            platform.gatts.single().lastWriteType,
        )
        platform.emitWrite(0, platform.gatts.single(), writeUuid, 0)
        platform.emitNotification(0, platform.gatts.single(), notifyUuid, rawPayload)

        val scan = events.filterIsInstance<BleTransportEvent.ScanReceived>().single()
        assertEquals(privateValue, scan.payload.value)
        assertEquals(InputSource.BLUETOOTH, scan.payload.source)
        assertTrue(events.none { it.toString().contains(rawPayload.contentToString()) })
        assertNotNull(scan.linkGeneration)
    }

    @Test
    fun staleGattCallbackAfterReconnectIsIgnored() {
        val platform = FakePlatform()
        val transport = newTransport(platform = platform)
        val events = mutableListOf<BleTransportEvent>()
        transport.listener = BleTransportListener { events += it }

        assertTrue(transport.connect(device))
        val firstGatt = platform.gatts[0]
        platform.emitConnected(0, firstGatt)
        platform.emitServices(0, firstGatt)
        transport.disconnect(device)
        assertTrue(transport.connect(device))
        val secondGatt = platform.gatts[1]
        platform.emitConnected(1, secondGatt)
        platform.emitServices(1, secondGatt)
        val connectedCount = events.count { it is BleTransportEvent.Connected }

        platform.gattCallbacks[0].onConnectionStateChanged(firstGatt, 0, true)
        platform.gattCallbacks[0].onServicesDiscovered(firstGatt, 0)
        assertEquals(connectedCount, events.count { it is BleTransportEvent.Connected })
        assertTrue(firstGatt.closeCalls >= 2)
        assertEquals(0, secondGatt.closeCalls)
    }

    private fun newTransport(
        platform: FakePlatform,
        scheduler: FakeScheduler = FakeScheduler(),
        permissions: FakePermissions = FakePermissions(),
    ): AndroidBleTransport = AndroidBleTransport(
        profile = AndroidBleProtocolProfile(
            serviceUuid = serviceUuid,
            readCharacteristicUuid = readUuid,
            writeCharacteristicUuid = writeUuid,
        ),
        scheduler = scheduler,
        permissionChecker = permissions,
        platform = platform,
    )

    private fun connectAndDiscover(transport: AndroidBleTransport, platform: FakePlatform) {
        assertTrue(transport.connect(device))
        val index = platform.gatts.lastIndex
        val gatt = platform.gatts[index]
        platform.emitConnected(index, gatt)
        platform.emitServices(index, gatt)
    }

    private class FakePermissions(
        var discovery: BlePermissionState = BlePermissionState.GRANTED,
        var connection: BlePermissionState = BlePermissionState.GRANTED,
    ) : BlePermissionChecker {
        override fun discoveryPermission(): BlePermissionState = discovery
        override fun connectionPermission(): BlePermissionState = connection
    }

    private class FakeScheduler : BleTimeoutScheduler {
        private data class Task(
            val delayMillis: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = ArrayDeque<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): BleTimeoutHandle {
            val scheduled = Task(delayMillis, task)
            tasks += scheduled
            return BleTimeoutHandle { scheduled.cancelled = true }
        }

        fun runNext(delayMillis: Long) {
            val task = tasks.firstOrNull { it.delayMillis == delayMillis && !it.cancelled }
                ?: error("No scheduled task for $delayMillis")
            task.task()
        }
    }

    private class FakePlatform : BlePlatform {
        override val availability: BleAvailability = BleAvailability.Ready
        var startDiscoveryCalls = 0
        var stopDiscoveryCalls = 0
        var connectCalls = 0
        val scanCallbacks = mutableListOf<BlePlatformScanCallback>()
        val gattCallbacks = mutableListOf<BlePlatformGattCallback>()
        val gatts = mutableListOf<FakeGatt>()

        override fun startDiscovery(serviceUuid: UUID, callback: BlePlatformScanCallback): Boolean {
            startDiscoveryCalls++
            scanCallbacks += callback
            return true
        }

        override fun stopDiscovery(callback: BlePlatformScanCallback) {
            stopDiscoveryCalls++
        }

        override fun connect(device: ScannerDevice, callback: BlePlatformGattCallback): BlePlatformGatt {
            connectCalls++
            gattCallbacks += callback
            return FakeGatt(device).also { gatts += it }
        }

        fun emitConnected(index: Int, gatt: FakeGatt) {
            gattCallbacks[index].onConnectionStateChanged(gatt, 0, true)
        }

        fun emitServices(index: Int, gatt: FakeGatt) {
            gattCallbacks[index].onServicesDiscovered(gatt, 0)
        }

        fun emitRead(index: Int, gatt: FakeGatt, uuid: UUID, status: Int, value: ByteArray) {
            gattCallbacks[index].onCharacteristicRead(gatt, uuid, status, value)
        }

        fun emitWrite(index: Int, gatt: FakeGatt, uuid: UUID, status: Int) {
            gattCallbacks[index].onCharacteristicWrite(gatt, uuid, status)
        }

        fun emitNotification(index: Int, gatt: FakeGatt, uuid: UUID, value: ByteArray) {
            gattCallbacks[index].onCharacteristicChanged(gatt, uuid, value)
        }
    }

    private class FakeGatt(
        override val device: ScannerDevice,
    ) : BlePlatformGatt {
        var discoverServicesAccepted = true
        var readAccepted = true
        var writeAccepted = true
        var notificationsAccepted = true
        var disconnectCalls = 0
        var closeCalls = 0
        var lastWriteType: Int? = null
        var lastPayload: ByteArray? = null

        override fun discoverServices(): Boolean = discoverServicesAccepted

        override fun hasCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean = true

        override fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): Boolean = readAccepted

        override fun writeCharacteristic(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            payload: ByteArray,
            writeType: Int,
        ): Boolean {
            lastPayload = payload
            lastWriteType = writeType
            return writeAccepted
        }

        override fun enableNotifications(
            serviceUuid: UUID,
            characteristicUuid: UUID,
            descriptorUuid: UUID?,
            enableValue: ByteArray?,
        ): Boolean = notificationsAccepted

        override fun disconnect() {
            disconnectCalls++
        }

        override fun close() {
            closeCalls++
        }
    }
}
