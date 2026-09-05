package jp.rimtty.codematch.feature.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.scanner.api.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraStageTest {
    @Test
    fun oversizedPreviewCannotPaintOutsideStoppedStageBounds() {
        composeRule.setContent {
            Box(
                Modifier.size(240.dp, 260.dp).background(Color.White).testTag("clip_root"),
                contentAlignment = Alignment.Center,
            ) {
                CameraStage(
                    format = jp.rimtty.codematch.scanner.api.ScanFormat.QR,
                    running = true,
                    modifier = Modifier.width(200.dp),
                    previewContent = { modifier, _ ->
                        Canvas(modifier) {
                            drawRect(Color.Red, Offset(0f, -size.height), Size(size.width, size.height * 3))
                        }
                    },
                )
            }
        }
        val pixels = composeRule.onNodeWithTag("clip_root").captureToImage().toPixelMap()
        val x = pixels.width / 2
        assertEquals(Color.White, pixels[x, pixels.height / 10])
        assertEquals(Color.White, pixels[x, pixels.height * 9 / 10])
        assertEquals(Color.Red, pixels[x, pixels.height / 2])
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun qrWaitingShowsSquareGuideAndAccessibleFocusInstruction() {
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.WaitingQr(),
                        inputSource = InputSource.CAMERA,
                    ),
                    sessionActive = true,
                ),
                onAction = {},
            )
        }

        composeRule.onNodeWithTag("scan_camera_stage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.scan_camera_stage_description,
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.scan_camera_qr_guide,
                ),
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.scan_camera_tap_to_focus,
                ),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun code128WaitingShowsWideGuide() {
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.WaitingCode128("QR"),
                        inputSource = InputSource.CAMERA,
                    ),
                    sessionActive = true,
                ),
                onAction = {},
            )
        }

        composeRule.onNodeWithTag("scan_camera_stage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.scan_camera_stage_description,
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.scan_camera_code128_guide,
                ),
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.scan_camera_tap_to_focus,
                ),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun tapOnStageEmitsNormalizedFocusPoint() {
        var focusPoint: CameraFocusPoint? = null
        composeRule.setContent {
            CameraStage(
                format = jp.rimtty.codematch.scanner.api.ScanFormat.QR,
                running = true,
                onFocus = { focusPoint = it },
            )
        }

        composeRule.onNodeWithTag("scan_camera_stage").performTouchInput {
            click()
        }
        composeRule.runOnIdle {
            assertTrue(focusPoint != null)
            assertEquals(.5f, focusPoint!!.xFraction, .02f)
            assertEquals(.5f, focusPoint!!.yFraction, .02f)
        }
    }

    @Test
    fun semanticFocusActionEmitsCenterPointForNonPointerUsers() {
        var focusPoint: CameraFocusPoint? = null
        composeRule.setContent {
            CameraStage(
                format = jp.rimtty.codematch.scanner.api.ScanFormat.QR,
                running = true,
                onFocus = { focusPoint = it },
            )
        }

        composeRule.onNodeWithTag("scan_camera_stage")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(CameraFocusPoint(.5f, .5f), focusPoint)
        }
    }

    @Test
    fun pointerFocusTracksRunningStateAcrossStartAndStop() {
        val running = mutableStateOf(false)
        var focusCount = 0
        composeRule.setContent {
            CameraStage(
                format = jp.rimtty.codematch.scanner.api.ScanFormat.QR,
                running = running.value,
                onFocus = { focusCount += 1 },
            )
        }

        composeRule.onNodeWithTag("scan_camera_stage").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, focusCount) }

        composeRule.runOnIdle { running.value = true }
        composeRule.onNodeWithTag("scan_camera_stage").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, focusCount) }

        composeRule.runOnIdle { running.value = false }
        composeRule.onNodeWithTag("scan_camera_stage").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, focusCount) }
    }

    @Test
    fun permanentlyDeniedPermissionOffersSettingsAction() {
        var openedSettings = false
        composeRule.setContent {
            ScanScreen(
                state = ScanUiState.fromSession(
                    session = ScanSessionState(
                        scan = ScanState.WaitingQr(),
                        inputSource = InputSource.CAMERA,
                    ),
                    sessionActive = true,
                    cameraPermissionDenied = true,
                    cameraPermissionState = CameraPermissionState.PERMANENTLY_DENIED,
                ),
                onAction = {},
                onOpenCameraSettings = { openedSettings = true },
            )
        }

        composeRule.onNodeWithTag("scan_camera_permission_permanently_denied")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("scan_camera_open_settings")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertTrue(openedSettings) }
    }
}
