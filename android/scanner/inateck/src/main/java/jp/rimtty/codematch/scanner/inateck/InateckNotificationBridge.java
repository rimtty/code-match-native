package jp.rimtty.codematch.scanner.inateck;

import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.exception.BleException;
import com.inateck.scanner.ble.BleListManager;
import com.inateck.scanner.ble.BleMessager;
import com.inateck.scanner.ble.BleScannerDevice;

/**
 * Java bridge for the SDK's Kotlin-internal accessors.
 *
 * The published 2.0.0 JAR exposes these methods with `$ble_release` names.
 * Java can call those names directly, while Kotlin correctly treats them as
 * internal to the vendor module. No reflection or binary modification is
 * required.
 */
final class InateckNotificationBridge {
    interface Callback {
        void onReady();
        void onFailure();
        void onCommandTraffic();
        void onBytes(byte[] value);
    }

    private InateckNotificationBridge() {}

    static void disableVendorLogging() {
        manager().enableLog(false);
    }

    static void install(BleScannerDevice device, Callback callback) {
        manager().notify(
                device.getDevice$ble_release(),
                BleMessager.serviceUUID,
                BleMessager.notifyUUID,
                new BleNotifyCallback() {
                    @Override
                    public void onNotifySuccess() {
                        callback.onReady();
                    }

                    @Override
                    public void onNotifyFailure(BleException exception) {
                        callback.onFailure();
                    }

                    @Override
                    public void onCharacteristicChanged(byte[] data) {
                        byte[] safeCopy = data == null ? new byte[0] : data.clone();
                        if (device.getMessager()
                                .getTaskManager$ble_release()
                                .getRunningTask() != null) {
                            callback.onCommandTraffic();
                            // Preserve the SDK's command parser for auth/settings
                            // replies without retaining its raw-byte Logcat call.
                            device.getMessager()
                                    .getTaskManager$ble_release()
                                    .receiveData(safeCopy);
                        } else {
                            callback.onBytes(safeCopy);
                        }
                    }
                });
    }

    static void stop(BleScannerDevice device) {
        manager().stopNotify(
                device.getDevice$ble_release(),
                BleMessager.serviceUUID,
                BleMessager.notifyUUID);
    }

    private static BleManager manager() {
        return BleListManager.INSTANCE.getManager$ble_release();
    }
}
