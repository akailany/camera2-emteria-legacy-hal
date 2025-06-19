/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.computeExifOrientation
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera2.basic.CameraActivity
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.net.Uri

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** LEGACY HAL FIX: Dynamically discover the first available camera with BACKWARD_COMPATIBLE capability */
    private val availableCameraId: String by lazy {
        discoverAvailableCamera()
    }

    /** [CameraCharacteristics] corresponding to the discovered Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        // LEGACY HAL FIX: Use dynamically discovered camera ID instead of hardcoded value
        cameraManager.getCameraCharacteristics(availableCameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** LEGACY HAL FIX: Track if still capture session is configured */
    private var stillCaptureConfigured = false

    /** LIFECYCLE FIX: Track camera initialization state to prevent concurrent operations */
    private var cameraInitializing = false

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    // UPSIDE DOWN FIX: Set to true if your camera preview is upside down
    // This now fixes BOTH preview (via TextureView matrix) AND saved photos (via JPEG_ORIENTATION)
    private val CAMERA_IS_UPSIDE_DOWN = false
    
    // COLOR FIX: Set to true to fix purple/magenta tint on Raspberry Pi cameras
    // This applies auto white balance and color correction to both preview and still capture
    private val ENABLE_COLOR_CORRECTION = true
    
    // COLOR FIX: Detect if we're on a legacy HAL device (like Raspberry Pi) vs regular Android
    private fun isLegacyHalDevice(): Boolean {
        return try {
            // Check if camera ID is non-standard (like "1000" on Raspberry Pi)
            val cameraId = availableCameraId
            val isNonStandard = cameraId != "0" && cameraId != "1"
            Log.d(TAG, "Device type detection: Camera ID = '$cameraId', Legacy HAL = $isNonStandard")
            isNonStandard
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine device type, defaulting to regular Android: ${e.message}")
            false
        }
    }
    
    // COLOR FIX: Get appropriate white balance mode for device type
    private fun getColorAwbMode(): Int {
        return if (isLegacyHalDevice()) {
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT  // Aggressive for Raspberry Pi
        } else {
            CaptureRequest.CONTROL_AWB_MODE_AUTO      // Conservative for regular Android
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "=== FRAGMENT onCreateView ===")
        
        if (CAMERA_IS_UPSIDE_DOWN) {
            Log.d(TAG, "UPSIDE DOWN FIX: ENABLED (affects preview + saved photos)")
        } else {
            Log.d(TAG, "UPSIDE DOWN FIX: DISABLED")
        }
        
        if (ENABLE_COLOR_CORRECTION) {
            val deviceType = if (isLegacyHalDevice()) "Legacy HAL (aggressive settings)" else "Regular Android (conservative settings)"
            Log.d(TAG, "COLOR FIX: ENABLED for $deviceType")
        } else {
            Log.d(TAG, "COLOR FIX: DISABLED")
        }
        
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        Log.d(TAG, "Fragment view binding created successfully")
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== FRAGMENT onViewCreated ===")
        
        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        Log.d(TAG, "Setting up TextureView listener...")
        
        fragmentCameraBinding.viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d(TAG, "=== SURFACE TEXTURE DESTROYED ===")
                return true
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "=== SURFACE TEXTURE SIZE CHANGED ===")
                Log.d(TAG, "Surface size: ${width}x${height}")
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called when the surface texture is updated (each frame)
                // Don't log here - too many calls
            }

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "=== SURFACE TEXTURE AVAILABLE ===")
                Log.d(TAG, "Surface texture: ${surface.javaClass.simpleName}")
                Log.d(TAG, "Surface size: ${width}x${height}")
                
                // Check if camera is already initialized and working
                val needsCameraInit = !::camera.isInitialized || !camera.isOpened()
                Log.d(TAG, "Camera initialization needed: $needsCameraInit")
                
                // Selects appropriate preview size and configures view finder
                // LEGACY HAL FIX: Use format-based sizing instead of class-based for legacy HAL compatibility
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceTexture::class.java,
                    ImageFormat.PRIVATE
                )
                Log.d(TAG, "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // Apply transformations after layout is complete
                Log.d(TAG, "Posting transformations to view thread...")
                view.post { 
                    applyPreviewTransformations()
                }

                // Only initialize camera if needed (prevents double initialization)
                if (needsCameraInit) {
                    Log.d(TAG, "Posting camera initialization to view thread...")
                    // To ensure that size is set, initialize camera in the view's thread
                    view.post { initializeCamera() }
                } else {
                    Log.d(TAG, "Camera already initialized and functional, skipping initialization")
                }
            }
        }

        Log.d(TAG, "Setting up orientation live data...")
        
        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
        
        Log.d(TAG, "=== FRAGMENT onViewCreated COMPLETE ===")
    }

    /**
     * Apply all preview transformations: aspect ratio fix + rotation (conditional)
     */
    private fun applyPreviewTransformations() {
        Log.d(TAG, "=== APPLYING PREVIEW TRANSFORMATIONS ===")
        
        try {
            val matrix = Matrix()
            val viewWidth = fragmentCameraBinding.viewFinder.width.toFloat()
            val viewHeight = fragmentCameraBinding.viewFinder.height.toFloat()
            
            Log.d(TAG, "TextureView dimensions: ${viewWidth} x ${viewHeight}")
            
            // ASPECT RATIO FIX: Apply height compression without affecting width
            // This maintains full screen width while reducing vertical stretching
            Log.d(TAG, "ASPECT RATIO FIX: Compressing height to 60% (more aggressive)")
            matrix.postScale(1.0f, 0.6f, viewWidth / 2, viewHeight / 2)
            
            // UPSIDE DOWN FIX: Conditionally apply 180° rotation
            if (CAMERA_IS_UPSIDE_DOWN) {
                Log.d(TAG, "UPSIDE DOWN FIX: Applying 180° rotation")
                matrix.postRotate(180f, viewWidth / 2, viewHeight / 2)
            } else {
                Log.d(TAG, "UPSIDE DOWN FIX: Skipped (disabled)")
            }
            
            fragmentCameraBinding.viewFinder.setTransform(matrix)
            Log.d(TAG, "Transformations applied: height=60%, width=100%")
            
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to apply transformations", exc)
        }
    }

    /**
     * After taking a photo, add your custom app logic here.
     * The photo has been saved successfully and you have access to both the file path and URI.
     * 
     * DEVICE-SPECIFIC BEHAVIOR:
     * - Regular Android: Real photo captured and saved, both path and URI are valid
     * - Legacy HAL (Emteria): Fake data provided, use your own hardcoded image instead
     * 
     * You can distinguish between real and fake data by checking the path:
     * - Real data: Actual file system path (e.g., "/data/data/.../IMG_2023_12_01_14_30_45_123.jpg")
     * - Fake data: Placeholder path (e.g., "/fake/camera/photo/placeholder_image.jpg")
     */
    private fun onPhotoCaptured(photoPath: String, photoUri: Uri) {
        Log.d(TAG, "Photo captured successfully: $photoPath")
        
        // Detect if this is real or fake data
        val isRealPhoto = !photoPath.startsWith("/fake/")
        
        if (isRealPhoto) {
            Log.d(TAG, "REAL PHOTO: Use actual captured image")
            // TODO: Add your app-specific logic for REAL photos here!
            // - Process the actual image file at photoPath
            // - Upload real image to server
            // - Extract real image data for analysis
            // - Navigate to processing screen with real image
        } else {
            Log.d(TAG, "FAKE PHOTO: Use your hardcoded placeholder image instead")
            // TODO: Add your app-specific logic for FAKE photos here!
            // - Use your own hardcoded/bundled image instead of photoPath
            // - Upload hardcoded image to server
            // - Use pre-calculated analysis results
            // - Navigate to processing screen with hardcoded data
        }
        
        // Common logic for both real and fake photos:
        // - Update UI to show "photo taken" state
        // - Navigate to next screen in your app flow
        // - Update app state/preferences
        // etc.
    }

    /**
     * LEGACY HAL FIX: Dynamically discover the first available camera with BACKWARD_COMPATIBLE capability
     */
    private fun discoverAvailableCamera(): String {
        Log.d(TAG, "=== DISCOVERING AVAILABLE CAMERAS ===")
        
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Found ${cameraIds.size} camera(s): ${cameraIds.contentToString()}")
            
            if (cameraIds.isEmpty()) {
                throw RuntimeException("No cameras found on device")
            }
            
            // Find the first camera with BACKWARD_COMPATIBLE capability (required for legacy HAL)
            for (cameraId in cameraIds) {
                Log.d(TAG, "Checking camera ID: $cameraId")
                
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    
                    Log.d(TAG, "Camera $cameraId capabilities: ${capabilities?.contentToString()}")
                    
                    if (capabilities != null && capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        val facingString = when (lensFacing) {
                            CameraCharacteristics.LENS_FACING_BACK -> "Back"
                            CameraCharacteristics.LENS_FACING_FRONT -> "Front" 
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                            else -> "Unknown"
                        }
                        
                        Log.d(TAG, "=== SELECTED CAMERA ===")
                        Log.d(TAG, "Camera ID: $cameraId")
                        Log.d(TAG, "Lens facing: $facingString")
                        Log.d(TAG, "Supports BACKWARD_COMPATIBLE: YES")
                        
                        return cameraId
                    } else {
                        Log.w(TAG, "Camera $cameraId does not support BACKWARD_COMPATIBLE, skipping")
                    }
                } catch (exc: Exception) {
                    Log.e(TAG, "Error checking camera $cameraId characteristics", exc)
                    continue
                }
            }
            
            // If no BACKWARD_COMPATIBLE camera found, use the first available camera as fallback
            val fallbackId = cameraIds[0]
            Log.w(TAG, "=== NO BACKWARD_COMPATIBLE CAMERA FOUND ===")
            Log.w(TAG, "Using fallback camera ID: $fallbackId")
            return fallbackId
            
        } catch (exc: Exception) {
            Log.e(TAG, "=== CAMERA DISCOVERY FAILED ===", exc)
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${exc.message}")
            
            // Final fallback to "0" if discovery completely fails
            Log.w(TAG, "Falling back to camera ID '0' as last resort")
            return "0"
        }
    }

    /**
     * DEVICE-ADAPTIVE CAMERA INITIALIZATION: Begin camera operations with device-appropriate settings.
     * This function:
     * - Opens the dynamically discovered camera ID
     * - Creates SINGLE-surface sessions for Legacy HAL (prevents crashes)
     * - Creates DUAL-surface sessions for regular Android (enables real photo capture)
     * - Applies device-specific camera parameters (aggressive for Legacy HAL, conservative for regular Android)
     * - Sets up still image capture functionality (real or fake based on device type)
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // LIFECYCLE FIX: Prevent concurrent initialization attempts
        if (cameraInitializing) {
            Log.d(TAG, "Camera initialization already in progress, skipping...")
            return@launch
        }
        
        // Check if camera is already open and functional
        if (::camera.isInitialized && camera.isOpened()) {
            Log.d(TAG, "Camera already open and functional, skipping initialization")
            return@launch
        }
        
        cameraInitializing = true
        
        try {
            // LEGACY HAL DEBUG: Log camera opening attempt
            Log.d(TAG, "=== CAMERA INITIALIZATION START ===")
            Log.d(TAG, "Attempting to open camera ID: $availableCameraId")
            
            // LEGACY HAL FIX: Open dynamically discovered camera ID
            camera = openCamera(cameraManager, availableCameraId, cameraHandler)
            
            // LEGACY HAL DEBUG: Log successful camera opening
            Log.d(TAG, "Camera opened successfully, creating capture session...")

            val isLegacy = isLegacyHalDevice()
            val previewSurface = Surface(fragmentCameraBinding.viewFinder.surfaceTexture)
            
            if (isLegacy) {
                Log.d(TAG, "=== LEGACY HAL APPROACH ===")
                Log.d(TAG, "Creating SINGLE-surface session for Legacy HAL device (prevents crashes)")
                val singleTargets = listOf(previewSurface)
                Log.d(TAG, "Legacy HAL targets list size: ${singleTargets.size}")
                session = createCaptureSession(camera, singleTargets, cameraHandler)
                Log.d(TAG, "Legacy HAL will use FAKE photo capture to avoid surface configuration errors")
            } else {
                Log.d(TAG, "=== REGULAR ANDROID APPROACH ===")
                Log.d(TAG, "Creating DUAL-surface session for regular Android device (enables real photo capture)")
                
                // Setup ImageReader for regular Android devices only
                setupStillCapture()
                
                val dualTargets = listOf(previewSurface, imageReader.surface)
                Log.d(TAG, "Regular Android targets list size: ${dualTargets.size}")
                session = createCaptureSession(camera, dualTargets, cameraHandler)
                Log.d(TAG, "Regular Android will use REAL photo capture with ImageReader")
            }

            // LEGACY HAL DEBUG: Log session creation success
            Log.d(TAG, "Capture session created successfully, starting preview...")

            // PREVIEW REQUEST: Single surface for both device types (only preview surface)
            val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW).apply { 
                addTarget(previewSurface)
                
                // SURFACE APPROACH: Preview-only request works for both single and dual surface sessions
                // Legacy HAL: Only surface in session, no issues
                // Regular Android: ImageReader surface will be used only during photo capture
                Log.d(TAG, "SURFACE APPROACH: Using preview-only capture request for compatibility")
                
                // COLOR FIX: Add white balance and color correction for Raspberry Pi cameras
                // This fixes the common purple/magenta tint issue on IMX219 cameras
                if (ENABLE_COLOR_CORRECTION) {
                    val isLegacy = isLegacyHalDevice()
                    if (isLegacy) {
                        Log.d(TAG, "COLOR FIX: Applying AGGRESSIVE settings for legacy HAL device")
                        
                        // Aggressive settings for Raspberry Pi / legacy HAL
                        set(CaptureRequest.CONTROL_AWB_MODE, getColorAwbMode())
                        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                        set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                    } else {
                        Log.d(TAG, "COLOR FIX: Applying CONSERVATIVE settings for regular Android device")
                        
                        // Conservative settings for regular Android devices
                        set(CaptureRequest.CONTROL_AWB_MODE, getColorAwbMode())
                        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                        // Don't set scene mode for regular devices - let them use default
                    }
                    
                    // Common settings for both device types
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    
                    val awbModeStr = when(getColorAwbMode()) {
                        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
                        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
                        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
                        CaptureRequest.CONTROL_AWB_MODE_AUTO -> "AUTO"
                        else -> "UNKNOWN"
                    }
                    val deviceType = if (isLegacy) "Legacy HAL" else "Regular Android"
                    Log.d(TAG, "COLOR FIX: Applied $awbModeStr white balance for $deviceType device")
                } else {
                    Log.d(TAG, "COLOR FIX: Skipped (disabled)")
                }
                
                // UPSIDE DOWN FIX: Apply 180° rotation if camera is upside down
                if (CAMERA_IS_UPSIDE_DOWN) {
                    Log.d(TAG, "UPSIDE DOWN FIX: Applying 180° rotation to preview capture")
                    Log.d(TAG, "NOTE: Preview is rotated via TextureView matrix, this affects saved photos")
                    set(CaptureRequest.JPEG_ORIENTATION, 180)
                }
            }

            Log.d(TAG, "Preview capture request created, starting repeating request...")

            // This will keep sending the capture request as frequently as possible until the
            // session is torn down or session.stopRepeating() is called
            session.setRepeatingRequest(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    Log.d(TAG, "=== PREVIEW CAPTURE STARTED ===")
                    Log.d(TAG, "Frame: $frameNumber, Timestamp: $timestamp")
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val frameNumber = result.frameNumber
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    Log.d(TAG, "=== PREVIEW CAPTURE COMPLETED ===")
                    Log.d(TAG, "Frame: $frameNumber, Timestamp: $timestamp")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "=== PREVIEW CAPTURE FAILED ===")
                    Log.e(TAG, "Frame: ${failure.frameNumber}, Reason: ${failure.reason}")
                }
            }, cameraHandler)
            Log.d(TAG, "Preview session started successfully")

            // Setup still capture button functionality with device-specific behavior:
            // - Legacy HAL: Fake photo capture (flash animation + placeholder data)
            // - Regular Android: Real photo capture (ImageReader + actual photo saving)
            setupStillCaptureButton()
            
            Log.d(TAG, "=== CAMERA INITIALIZATION COMPLETE ===")

        } catch (exc: Exception) {
            Log.e(TAG, "=== CAMERA INITIALIZATION FAILED ===", exc)
            Log.e(TAG, "Failed to initialize camera for legacy HAL: ${exc.message}")
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            // Show user-friendly error message
            // You could add a toast or error dialog here
        } finally {
            // LIFECYCLE FIX: Always reset the initialization flag
            cameraInitializing = false
        }
    }

    /**
     * Setup still capture button functionality with device-specific behavior:
     * - Legacy HAL: Fake photo capture (flash animation + placeholder data)
     * - Regular Android: Real photo capture (ImageReader + actual photo saving)
     */
    private fun setupStillCaptureButton() {
        Log.d(TAG, "=== SETTING UP STILL CAPTURE BUTTON ===")
        
        // Verify button exists and is accessible
        try {
            val button = fragmentCameraBinding.captureButton
            Log.d(TAG, "Capture button found: ${button.javaClass.simpleName}")
            Log.d(TAG, "Button visibility: ${button.visibility}")
            Log.d(TAG, "Button clickable: ${button.isClickable}")
            Log.d(TAG, "Button enabled: ${button.isEnabled}")
            Log.d(TAG, "Button size: ${button.layoutParams.width}x${button.layoutParams.height}")
        } catch (exc: Exception) {
            Log.e(TAG, "Error accessing capture button", exc)
            return
        }
        
        val isLegacy = isLegacyHalDevice()
        val captureType = if (isLegacy) "FAKE (Legacy HAL)" else "REAL (Regular Android)"
        Log.d(TAG, "Capture type: $captureType")
        
        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {
            Log.d(TAG, "=== CAPTURE BUTTON CLICKED ===")
            Log.d(TAG, "Device type: ${if (isLegacy) "Legacy HAL" else "Regular Android"}")
            
            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false

            if (isLegacy) {
                // LEGACY HAL: Fake photo capture with realistic UX
                Log.d(TAG, "=== FAKE PHOTO CAPTURE (Legacy HAL) ===")
                handleFakePhotoCapture(it)
            } else {
                // REGULAR ANDROID: Real photo capture with ImageReader
                Log.d(TAG, "=== REAL PHOTO CAPTURE (Regular Android) ===")
                handleRealPhotoCapture(it)
            }
        }
        
        Log.d(TAG, "Capture button listener configured successfully for $captureType")
    }

    /**
     * Handle fake photo capture for Legacy HAL devices:
     * - Trigger white flash animation (same as real capture)
     * - Short delay (to feel realistic)
     * - Call onPhotoCaptured() with placeholder data
     * - Re-enable capture button
     */
    private fun handleFakePhotoCapture(captureButton: View) {
        Log.d(TAG, "Starting fake photo capture sequence...")
        
        // Trigger the same white flash animation as real capture
        fragmentCameraBinding.viewFinder.post(animationTask)
        Log.d(TAG, "Flash animation triggered")
        
        // Realistic delay to make it feel like actual photo processing
        lifecycleScope.launch(Dispatchers.Main) {
            delay(500) // Half second delay for realism
            
            Log.d(TAG, "Creating placeholder photo data...")
            
            // Create fake file path and URI for consistency with app flow
            val fakePath = "/fake/camera/photo/placeholder_image.jpg"
            val fakeUri = Uri.parse("content://fake.camera.provider/placeholder_image.jpg")
            
            Log.d(TAG, "Fake photo data created:")
            Log.d(TAG, "  Fake path: $fakePath")
            Log.d(TAG, "  Fake URI: $fakeUri")
            
            // Call the integration function with placeholder data
            onPhotoCaptured(fakePath, fakeUri)
            
            // Re-enable capture button
            captureButton.isEnabled = true
            Log.d(TAG, "Fake photo capture sequence completed")
        }
    }

    /**
     * Handle real photo capture for regular Android devices:
     * - Verify ImageReader is configured
     * - Take actual photo using ImageReader
     * - Save photo to disk
     * - Call onPhotoCaptured() with real data
     * - Re-enable capture button
     */
    private fun handleRealPhotoCapture(captureButton: View) {
        // ImageReader should already be configured during initialization for regular Android devices
        if (!stillCaptureConfigured) {
            Log.w(TAG, "Still capture not configured - this should not happen on regular Android! Setting up now...")
            try {
                setupStillCapture()
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to setup still capture", exc)
                captureButton.post { captureButton.isEnabled = true }
                return
            }
        } else {
            Log.d(TAG, "Still capture already configured, proceeding with real photo...")
        }

        // Perform I/O heavy operations in a different scope
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                                ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }

                    // Display the photo taken to user
                    // Call integration function for app-specific processing
                    lifecycleScope.launch(Dispatchers.Main) {
                        val photoUri = Uri.fromFile(output)
                        onPhotoCaptured(output.absolutePath, photoUri)
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to take photo", exc)
            }

            // Re-enable click listener after photo is taken
            captureButton.post { captureButton.isEnabled = true }
        }
    }

    /**
     * Setup ImageReader for still capture - called during initialization for REGULAR ANDROID devices only
     * Legacy HAL devices skip this entirely and use fake photo capture instead
     */
    private fun setupStillCapture() {
        Log.d(TAG, "=== SETTING UP STILL CAPTURE ===")
        
        // Safety check: This should only be called for regular Android devices
        if (isLegacyHalDevice()) {
            Log.w(TAG, "WARNING: setupStillCapture() called on Legacy HAL device - this should not happen!")
            Log.w(TAG, "Legacy HAL devices should use fake photo capture instead")
            return
        }
        
        // Check if already configured
        if (stillCaptureConfigured) {
            Log.d(TAG, "Still capture already configured, skipping setup")
            return
        }
        
        // LEGACY HAL FIX: Use JPEG format for legacy HAL compatibility
        val pixelFormat = ImageFormat.JPEG
        Log.d(TAG, "Using pixel format: $pixelFormat (JPEG)")
        
        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(pixelFormat).maxByOrNull { it.height * it.width }!!
        
        Log.d(TAG, "Selected output size: ${size.width}x${size.height}")
        Log.d(TAG, "Creating ImageReader with buffer size: $IMAGE_BUFFER_SIZE")
        
        try {
            imageReader = ImageReader.newInstance(
                    size.width, size.height, pixelFormat, IMAGE_BUFFER_SIZE)
            
            stillCaptureConfigured = true
            Log.d(TAG, "=== STILL CAPTURE CONFIGURED SUCCESSFULLY ===")
            Log.d(TAG, "ImageReader created: ${size.width}x${size.height}, format: $pixelFormat")
            
        } catch (exc: Exception) {
            Log.e(TAG, "=== FAILED TO CREATE IMAGE READER ===", exc)
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${exc.message}")
            throw exc
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        
        // LEGACY HAL DEBUG: Log camera manager details
        Log.d(TAG, "=== OPENING CAMERA ===")
        Log.d(TAG, "Camera manager: ${manager.javaClass.simpleName}")
        Log.d(TAG, "Requesting camera ID: $cameraId")
        Log.d(TAG, "Handler: ${handler?.toString() ?: "null"}")
        
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "=== CAMERA DEVICE OPENED ===")
                    Log.d(TAG, "CameraDevice opened successfully: ${device.id}")
                    Log.d(TAG, "Device class: ${device.javaClass.simpleName}")
                    cont.resume(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "=== CAMERA DEVICE DISCONNECTED ===")
                    Log.w(TAG, "Camera $cameraId has been disconnected")
                    Log.w(TAG, "Device: ${device.id}")
                    requireActivity().finish()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    
                    Log.e(TAG, "=== CAMERA DEVICE ERROR ===")
                    Log.e(TAG, "Camera $cameraId error code: $error")
                    Log.e(TAG, "Error message: $msg")
                    Log.e(TAG, "Device: ${device.id}")
                    
                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)
                    
                    // LEGACY HAL FIX: Enhanced error logging for CAMERA_IN_USE
                    if (error == ERROR_CAMERA_IN_USE) {
                        Log.e(TAG, "LEGACY HAL ERROR: Camera $cameraId is in use. This often happens with legacy HAL when trying to open multiple surfaces or if another app is using the camera.")
                    }
                    
                    if (cont.isActive) cont.resumeWithException(exc)
                }
            }, handler)
            
            Log.d(TAG, "manager.openCamera() call completed, waiting for callback...")
            
        } catch (exc: Exception) {
            Log.e(TAG, "Exception during manager.openCamera() call", exc)
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${exc.message}")
            if (cont.isActive) cont.resumeWithException(exc)
        }
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        Log.d(TAG, "=== CREATING CAPTURE SESSION ===")
        Log.d(TAG, "Device: ${device.id}")
        Log.d(TAG, "Targets count: ${targets.size}")
        Log.d(TAG, "Handler: ${handler?.toString() ?: "null"}")
        
        try {
            // Create a capture session using the predefined targets; this also involves defining the
            // session state callback to be notified of when the session is ready
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "=== SESSION CONFIGURED SUCCESSFULLY ===")
                    Log.d(TAG, "Session: ${session.javaClass.simpleName}")
                    Log.d(TAG, "Session device: ${session.device?.id ?: "null"}")
                    cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "=== SESSION CONFIGURATION FAILED ===")
                    Log.e(TAG, "Session: ${session.javaClass.simpleName}")
                    Log.e(TAG, "Session device: ${session.device?.id ?: "null"}")
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e(TAG, exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }, handler)
            
            Log.d(TAG, "device.createCaptureSession() call completed, waiting for callback...")
            
        } catch (exc: Exception) {
            Log.e(TAG, "=== EXCEPTION IN CREATE CAPTURE SESSION ===", exc)
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${exc.message}")
            cont.resumeWithException(exc)
        }
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        Log.d(TAG, "=== TAKING STILL PHOTO ===")
        Log.d(TAG, "Flushing any leftover images from ImageReader...")

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        Log.d(TAG, "Setting up image queue and ImageReader listener...")

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        Log.d(TAG, "Creating still capture request...")
        val captureRequest = session.device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE).apply { 
            addTarget(imageReader.surface)
            
            // COLOR FIX: Add white balance and color correction for Raspberry Pi cameras
            // This ensures captured photos have the same color correction as preview
            if (ENABLE_COLOR_CORRECTION) {
                val isLegacy = isLegacyHalDevice()
                if (isLegacy) {
                    Log.d(TAG, "COLOR FIX: Applying AGGRESSIVE settings for legacy HAL device")
                    
                    // Aggressive settings for Raspberry Pi / legacy HAL
                    set(CaptureRequest.CONTROL_AWB_MODE, getColorAwbMode())
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                    set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                } else {
                    Log.d(TAG, "COLOR FIX: Applying CONSERVATIVE settings for regular Android device")
                    
                    // Conservative settings for regular Android devices
                    set(CaptureRequest.CONTROL_AWB_MODE, getColorAwbMode())
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                    // Don't set scene mode for regular devices - let them use default
                }
                
                // Common settings for both device types
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                
                val awbModeStr = when(getColorAwbMode()) {
                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
                    CaptureRequest.CONTROL_AWB_MODE_AUTO -> "AUTO"
                    else -> "UNKNOWN"
                }
                val deviceType = if (isLegacy) "Legacy HAL" else "Regular Android"
                Log.d(TAG, "COLOR FIX: Applied $awbModeStr white balance for $deviceType device")
            } else {
                Log.d(TAG, "COLOR FIX: Skipped for still capture (disabled)")
            }
            
            // UPSIDE DOWN FIX: Apply same 180° rotation to still capture
            if (CAMERA_IS_UPSIDE_DOWN) {
                Log.d(TAG, "UPSIDE DOWN FIX: Applying 180° rotation to still capture")
                set(CaptureRequest.JPEG_ORIENTATION, 180)
            }
        }
        
        Log.d(TAG, "Sending still capture request to session...")
        
        try {
            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    Log.d(TAG, "=== STILL CAPTURE STARTED ===")
                    Log.d(TAG, "Frame: $frameNumber, Timestamp: $timestamp")
                    fragmentCameraBinding.viewFinder.post(animationTask)
                }

                override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    Log.d(TAG, "=== STILL CAPTURE COMPLETED ===")
                    Log.d(TAG, "Capture result received: $resultTimestamp")
                    Log.d(TAG, "Frame: ${result.frameNumber}")

                    // Set a timeout in case image captured is dropped from the pipeline
                    val exc = TimeoutException("Image dequeuing took too long")
                    val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                    imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                    Log.d(TAG, "Starting image dequeue process...")

                    // Loop in the coroutine's context until an image with matching timestamp comes
                    // We need to launch the coroutine context again because the callback is done in
                    //  the handler provided to the `capture` method, not in our coroutine context
                    @Suppress("BlockingMethodInNonBlockingContext")
                    lifecycleScope.launch(cont.context) {
                        while (true) {

                            // Dequeue images while timestamps don't match
                            val image = imageQueue.take()
                            Log.d(TAG, "Dequeued image with timestamp: ${image.timestamp}")
                            // TODO(owahltinez): b/142011420
                            // if (image.timestamp != resultTimestamp) continue
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    image.format != ImageFormat.DEPTH_JPEG &&
                                    image.timestamp != resultTimestamp) continue
                            Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                            // Unset the image reader listener
                            imageReaderHandler.removeCallbacks(timeoutRunnable)
                            imageReader.setOnImageAvailableListener(null, null)

                            // Clear the queue of images, if there are left
                            while (imageQueue.size > 0) {
                                imageQueue.take().close()
                            }

                            // Compute EXIF orientation metadata
                            val rotation = relativeOrientation.value ?: 0
                            val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                    CameraCharacteristics.LENS_FACING_FRONT
                            val exifOrientation = computeExifOrientation(rotation, mirrored)

                            Log.d(TAG, "Still capture process completed successfully")

                            // Build the result and resume progress
                            cont.resume(CombinedCaptureResult(
                                    image, result, exifOrientation, imageReader.imageFormat))

                            // There is no need to break out of the loop, this coroutine will suspend
                        }
                    }
                }
                
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "=== STILL CAPTURE FAILED ===")
                    Log.e(TAG, "Frame: ${failure.frameNumber}, Reason: ${failure.reason}")
                    cont.resumeWithException(RuntimeException("Still capture failed: ${failure.reason}"))
                }
            }, cameraHandler)
            
            Log.d(TAG, "session.capture() call completed, waiting for callback...")
            
        } catch (exc: Exception) {
            Log.e(TAG, "=== EXCEPTION IN STILL CAPTURE ===", exc)
            Log.e(TAG, "Exception type: ${exc.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${exc.message}")
            cont.resumeWithException(exc)
        }
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val output = createFile(requireContext(), "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // When the format is RAW we use the DngCreator utility library
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    val output = createFile(requireContext(), "dng")
                    FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "=== FRAGMENT onStop ===")
        try {
            Log.d(TAG, "Closing camera device...")
            camera.close()
            Log.d(TAG, "Camera device closed successfully")
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== FRAGMENT onDestroy ===")
        Log.d(TAG, "Shutting down camera thread...")
        cameraThread.quitSafely()
        Log.d(TAG, "Shutting down image reader thread...")
        imageReaderThread.quitSafely()
        Log.d(TAG, "Fragment cleanup complete")
    }

    override fun onDestroyView() {
        Log.d(TAG, "=== FRAGMENT onDestroyView ===")
        _fragmentCameraBinding = null
        Log.d(TAG, "Fragment view binding cleared")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== FRAGMENT onResume ===")
        
        // Check if we need to reinitialize the camera
        // This handles cases like:
        // - Returning from background (recent apps)
        // - Unlocking phone
        // - Returning from another app
        if (::fragmentCameraBinding.isInitialized && 
            fragmentCameraBinding.viewFinder.isAvailable) {
            
            Log.d(TAG, "TextureView available, checking camera state...")
            
            if (::camera.isInitialized && !camera.isOpened()) {
                Log.d(TAG, "Camera was closed, reinitializing...")
                initializeCamera()
            } else if (!::camera.isInitialized) {
                Log.d(TAG, "Camera not initialized, starting fresh...")
                initializeCamera()
            } else {
                Log.d(TAG, "Camera still open and functional")
            }
        } else {
            Log.d(TAG, "TextureView not available yet, will initialize when surface becomes available")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "=== FRAGMENT onPause ===")
        
        // Close camera when going to background to free resources
        // This handles cases like:
        // - Going to recent apps view
        // - Locking phone  
        // - Switching to another app
        try {
            if (::camera.isInitialized && camera.isOpened()) {
                Log.d(TAG, "Closing camera due to onPause...")
                camera.close()
                Log.d(TAG, "Camera closed successfully in onPause")
            }
            
            // LIFECYCLE FIX: Reset state flags to ensure clean restart
            cameraInitializing = false
            stillCaptureConfigured = false
            Log.d(TAG, "State flags reset for clean restart")
            
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera in onPause", exc)
            // LIFECYCLE FIX: Reset flags even if there was an error
            cameraInitializing = false
            stillCaptureConfigured = false
        }
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
                val image: Image,
                val metadata: CaptureResult,
                val orientation: Int,
                val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}

/**
 * Extension function to safely check if camera device is still opened
 */
private fun CameraDevice?.isOpened(): Boolean {
    return try {
        this?.id != null
    } catch (e: Exception) {
        false
    }
}
