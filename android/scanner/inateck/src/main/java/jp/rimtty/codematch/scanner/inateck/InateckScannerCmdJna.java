package jp.rimtty.codematch.scanner.inateck;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.util.Objects;

/**
 * JNA boundary for Inateck's standalone scanner command parser.
 *
 * <p>The Bluetooth JAR ships a different native library ({@code
 * libscanner_cmd.so}).  The current official parsing library is loaded by its
 * own name so the two libraries can be present in the same APK without JNA
 * resolving the notification parser to the JAR's older library.</p>
 *
 * <p>The C header declares the data length as {@code uintptr_t}.  Android's
 * arm64 ABI therefore maps it to a Java {@code long}; callers use the
 * length-checked helpers below instead of invoking the native interface
 * directly.</p>
 */
public final class InateckScannerCmdJna {
    /** Name passed to JNA; JNA supplies the platform's {@code lib} prefix. */
    public static final String LIBRARY_NAME = "inateck_scanner_cmd";

    /** Native functions used by the scanner adapter. */
    public interface Api extends Library {
        String inateck_scanner_cmd_notify_data_result(byte[] data, long length);

        String inateck_scanner_cmd_get_hid_output(byte outputType);

        int inateck_scanner_cmd_check_result(byte[] data, long length);
    }

    private InateckScannerCmdJna() {}

    /** Loads the standalone official parser library lazily at the call site. */
    public static Api load() {
        return Native.load(LIBRARY_NAME, Api.class);
    }

    /**
     * Calls the notification parser with the complete byte array.
     *
     * <p>A copy is passed across the native boundary so the native call cannot
     * observe a caller mutating a reusable Bluetooth callback buffer.</p>
     */
    public static String notifyDataResult(Api api, byte[] data) {
        Objects.requireNonNull(data, "data");
        return notifyDataResult(api, data, data.length);
    }

    /** Calls the notification parser with an explicit, validated byte count. */
    public static String notifyDataResult(Api api, byte[] data, int length) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(data, "data");
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("native data length is out of bounds");
        }
        return api.inateck_scanner_cmd_notify_data_result(data.clone(), (long) length);
    }

    /**
     * Gets an official HID output command.  The SDK documents only output
     * types 0 (HID) and 1 (SDK), so reject every other value before native
     * code is reached.
     */
    public static String hidOutputResult(Api api, int outputType) {
        Objects.requireNonNull(api, "api");
        if (outputType != 0 && outputType != 1) {
            throw new IllegalArgumentException("unsupported HID output type");
        }
        return api.inateck_scanner_cmd_get_hid_output((byte) outputType);
    }

    /** Validates the device response to a command written without response. */
    public static boolean checkResult(Api api, byte[] data) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return false;
        }
        return api.inateck_scanner_cmd_check_result(data.clone(), (long) data.length) == 0;
    }
}
