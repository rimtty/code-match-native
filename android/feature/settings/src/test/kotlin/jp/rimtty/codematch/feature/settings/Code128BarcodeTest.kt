package jp.rimtty.codematch.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Code128BarcodeTest {
    @Test
    fun setupCodesKeepTheScannerOrderAndExactAsciiMessages() {
        assertEquals(
            listOf(
                BluetoothScannerSetupCode.ENTER_SETUP,
                BluetoothScannerSetupCode.GATT_MODE,
                BluetoothScannerSetupCode.SAVE_AND_EXIT,
            ),
            BluetoothScannerSetupCode.entries,
        )
        assertEquals(
            listOf("/*EnterSet*/", "/*BLE_GATT*/", "/*ExitSave*/"),
            BluetoothScannerSetupCode.entries.map(BluetoothScannerSetupCode::rawValue),
        )
    }

    @Test
    fun enterSetupMatchesCoreImageCode128BVectorAndDimensions() {
        val barcode = Code128Encoder.encode(BluetoothScannerSetupCode.ENTER_SETUP)

        assertEquals(
            listOf(104, 15, 10, 37, 78, 84, 69, 82, 51, 69, 84, 10, 15, 9, 106),
            barcode.encodedValues,
        )
        assertEquals(167, barcode.symbolModules)
        assertEquals(203, barcode.widthModules)
        assertEquals(68, barcode.heightModules)
        assertEquals(18, barcode.quietZoneModules)
        assertEquals(32, barcode.barHeightModules)
        assertTrue(barcode.modules.take(18).all { !it })
        assertTrue(barcode.modules.takeLast(18).all { !it })
        assertTrue(barcode.modules.drop(18).dropLast(18).any { it })
    }

    @Test
    fun gattAndSaveCodesUseDeterministicChecksums() {
        val gatt = Code128Encoder.encode(BluetoothScannerSetupCode.GATT_MODE)
        val save = Code128Encoder.encode(BluetoothScannerSetupCode.SAVE_AND_EXIT)

        assertEquals(14, gatt.encodedValues[gatt.encodedValues.lastIndex - 1])
        assertEquals(85, save.encodedValues[save.encodedValues.lastIndex - 1])
        assertEquals(203, gatt.widthModules)
        assertEquals(203, save.widthModules)
        assertEquals(gatt.modules, Code128Encoder.encode(BluetoothScannerSetupCode.GATT_MODE).modules)
        assertEquals(save.modules, Code128Encoder.encode(BluetoothScannerSetupCode.SAVE_AND_EXIT).modules)
    }

    @Test
    fun quietZoneAndBarHeightCanBeChangedWithoutChangingEncodedSymbols() {
        val standard = Code128Encoder.encode("ABC")
        val custom = Code128Encoder.encode("ABC", quietZoneModules = 9, barHeightModules = 40)

        assertEquals(standard.encodedValues, custom.encodedValues)
        assertEquals(9, custom.quietZoneModules)
        assertEquals(40, custom.barHeightModules)
        assertEquals(standard.symbolModules + 18, custom.widthModules)
        assertEquals(58, custom.heightModules)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPrintableInputIsRejectedInsteadOfBeingEncodedDifferently() {
        Code128Encoder.encode("setup\n")
    }

    @Test
    fun moduleVectorContainsOnlyBlackAndWhiteModules() {
        val modules = Code128Encoder.encode("/*BLE_GATT*/").modules

        assertTrue(modules.any { it })
        assertTrue(modules.any { !it })
        assertFalse(modules.first())
        assertFalse(modules.last())
    }
}
