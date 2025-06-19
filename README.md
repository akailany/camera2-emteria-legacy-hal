# Camera2Basic for Emteria OS & Raspberry Pi CM4 Integration

## Overview

This is a modified Android Camera2Basic sample specifically optimized for **Emteria OS on Raspberry Pi CM4 Nano C** and other legacy HAL devices. The camera app has been redesigned to work seamlessly with devices that only support BACKWARD_COMPATIBLE camera capabilities, making it perfect for industrial, IoT, and embedded Android applications.

## Key Features for Emteria OS Applications

**Legacy HAL Support** - Works on Raspberry Pi CM4 with Emteria OS (Android 11)  
**Dynamic Camera Discovery** - Automatically finds available cameras (handles camera ID "1000" etc.)  
**Single-Surface Architecture** - Prevents CAMERA_IN_USE errors on legacy devices  
**App Integration Hook** - Ready-to-use function for custom photo processing workflows  
**Comprehensive Logging** - Full debugging support for deployment troubleshooting  
**Orientation Support** - Configurable camera rotation for different hardware setups  

## Project Structure

```
Camera2Basic/
├── README.md                              # This integration guide
├── .gitignore                             # Git ignore rules
├── app/
│   ├── build.gradle                       # Module dependencies
│   ├── src/main/
│   │   ├── java/com/example/android/camera2/basic/
│   │   │   ├── CameraActivity.kt          # Main activity
│   │   │   ├── AutoFitTextureView.kt      # Custom TextureView with matrix transforms
│   │   │   ├── fragments/
│   │   │   │   ├── CameraFragment.kt      # MAIN CAMERA LOGIC
│   │   │   │   ├── PermissionsFragment.kt # Camera permissions handling
│   │   │   │   └── SelectorFragment.kt    # Camera/format selection
│   │   │   └── utils/
│   │   │       ├── CameraSizes.kt         # Preview size calculations
│   │   │       ├── OrientationLiveData.kt # Device orientation tracking
│   │   │       └── ComputeExifOrientation.kt # EXIF metadata handling
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── fragment_camera.xml    # Portrait camera layout
│   │   │   │   ├── fragment_camera_land.xml # Landscape camera layout
│   │   │   │   ├── activity_camera.xml    # Main activity layout
│   │   │   │   └── fragment_*.xml         # Other UI layouts
│   │   │   ├── drawable/
│   │   │   │   └── ic_capture.xml         # Professional capture button design
│   │   │   ├── navigation/
│   │   │   │   └── nav_graph.xml          # Navigation flow (cleaned up)
│   │   │   └── values/
│   │   │       ├── strings.xml            # App strings
│   │   │       └── themes.xml             # UI themes
│   │   └── AndroidManifest.xml            # App permissions & components
│   └── proguard-rules.pro                 # Code obfuscation rules
├── build.gradle                           # Project-level build config
├── gradle.properties                      # Gradle configuration
└── settings.gradle                        # Module settings
```

### Key Files for Integration

| File | Purpose | Required for Integration |
|------|---------|-------------------------|
| `CameraFragment.kt` | **Core camera logic** - Contains `onPhotoCaptured()` hook | Essential |
| `AutoFitTextureView.kt` | Custom TextureView with aspect ratio & rotation fixes | Essential |
| `utils/CameraSizes.kt` | Preview size calculation for legacy HAL | Essential |
| `utils/OrientationLiveData.kt` | Device orientation tracking | Essential |
| `utils/ComputeExifOrientation.kt` | Photo metadata handling | Essential |
| `fragment_camera.xml` | Portrait camera UI layout | Essential |
| `fragment_camera_land.xml` | Landscape camera UI layout | Essential |
| `ic_capture.xml` | Professional capture button design | Optional (can customize) |
| `nav_graph.xml` | Navigation configuration | Optional (depends on your nav setup) |

## Quick Integration Guide

### 1. Copy Camera Functionality

Copy these files to your Android app:

```
app/src/main/java/com/example/android/camera2/basic/
├── fragments/CameraFragment.kt           # Main camera logic
├── AutoFitTextureView.kt                 # Custom TextureView for proper scaling
└── utils/                                # Camera utility classes
    ├── CameraSizes.kt
    ├── OrientationLiveData.kt
    └── ComputeExifOrientation.kt

app/src/main/res/layout/
├── fragment_camera.xml                   # Portrait camera layout
└── fragment_camera_land.xml              # Landscape camera layout

app/src/main/res/drawable/
└── ic_capture.xml                        # Capture button design
```

### 2. Add Dependencies

Add to your `build.gradle` (Module: app):

```kotlin
dependencies {
    implementation "androidx.camera:camera-core:1.1.0"
    implementation "androidx.camera:camera-camera2:1.1.0"
    implementation "androidx.exifinterface:exifinterface:1.3.3"
    implementation "androidx.navigation:navigation-fragment-ktx:2.4.2"
    implementation "androidx.navigation:navigation-ui-ktx:2.4.2"
}
```

### 3. Add Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera2.full" android:required="false" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

### 4. Integration Point - The Key Function

The camera is ready for your app integration via this function in `CameraFragment.kt`:

```kotlin
/**
 * After taking a photo, add your custom app logic here.
 * The photo has been saved successfully and you have access to both the file path and URI.
 * You can navigate to your processing screen or do whatever you need with the photo.
 */
private fun onPhotoCaptured(photoPath: String, photoUri: Uri) {
    Log.d(TAG, "Photo captured successfully: $photoPath")
    
    // TODO: Add your app-specific logic here!
    // Process the image, send to server, navigate to next screen, etc.
}
```

## Application Integration Examples

### Example: Navigate to Image Processing Screen

```kotlin
private fun onPhotoCaptured(photoPath: String, photoUri: Uri) {
    Log.d(TAG, "Photo captured successfully: $photoPath")
    
    // Navigate to your image processing screen
    val action = CameraFragmentDirections.actionCameraToImageProcessor(
        photoPath = photoPath,
        photoUri = photoUri.toString()
    )
    navController.navigate(action)
}

## Configuration Options

### Camera Orientation

If your camera preview appears upside down, change this setting in `CameraFragment.kt`:

```kotlin
// Set to true if your camera preview is upside down
private val CAMERA_IS_UPSIDE_DOWN = true
```

This fixes both preview display AND saved photo orientation.

### Layout Customization

- **Portrait Layout**: `app/src/main/res/layout/fragment_camera.xml`
- **Landscape Layout**: `app/src/main/res/layout/fragment_camera_land.xml`
- **Capture Button**: `app/src/main/res/drawable/ic_capture.xml`

### Background Color

For professional camera app appearance, layouts use black backgrounds:

```xml
android:background="@android:color/black"
```

## Technical Implementation Details

### Legacy HAL Compatibility

The app implements several fixes for legacy HAL devices:

1. **Dynamic Camera Discovery**: Finds cameras with BACKWARD_COMPATIBLE capability
2. **Single-Surface Sessions**: Prevents multi-surface conflicts
3. **Deferred ImageReader Setup**: Only configures still capture when needed
4. **TextureView Implementation**: Better rotation support than SurfaceView

### Performance Optimizations

- **Preview-only initialization**: Camera starts faster
- **Lazy ImageReader creation**: Reduces memory usage
- **Proper thread management**: Separate threads for camera and image processing
- **Comprehensive error handling**: Detailed logging for debugging

### Aspect Ratio Handling

The app automatically handles camera preview stretching:

```kotlin
// Compresses height to 60% for proper aspect ratio
matrix.postScale(1.0f, 0.6f, viewWidth / 2, viewHeight / 2)
```

## Hardware Compatibility

### Tested Configurations

**Raspberry Pi CM4 Nano C + Emteria OS (Android 11)** - Primary target  
**IMX219 CSI Camera Module** - Recommended camera  
**Legacy HAL devices** - Any device with BACKWARD_COMPATIBLE capability  

### Camera Module Compatibility

| Camera Module | Status | Notes |
|---------------|--------|-------|
| IMX219 CSI | Tested | Recommended, excellent image quality |
| OV5647 | Should work | Not extensively tested |
| USB Cameras | Limited | May work with USB OTG, camera ID varies |

### Emteria OS Versions

- **Android 11**: Fully tested and supported
- **Android 10**: Should work, not extensively tested
- **Android 12+**: May require minor adjustments

## Deployment Guide

### For Raspberry Pi CM4 Nano C + Emteria OS

1. **Camera Module**: Connect IMX219 CSI camera (recommended)
2. **Camera ID**: App automatically discovers (usually "1000", not "0")
3. **Emteria Version**: Android 11 (tested)
4. **HAL Type**: Legacy HAL (BACKWARD_COMPATIBLE)

### Testing Checklist

Camera preview displays correctly  
Preview orientation is correct  
Capture button responds to clicks  
Photos are saved successfully  
`onPhotoCaptured()` function is called  
Photo orientation is correct in saved files  
No CAMERA_IN_USE errors  
App works after device rotation  

## Debugging

### Enable Debug Logging

The app includes comprehensive logging. To view camera debug logs:

```bash
adb logcat | grep "CameraFragment"
```

### Common Issues

| Issue | Solution |
|-------|----------|
| Camera ID not found | Check `discoverAvailableCamera()` logs |
| CAMERA_IN_USE error | Verify single-surface session configuration |
| Preview upside down | Set `CAMERA_IS_UPSIDE_DOWN = true` |
| Photos stretched | Check aspect ratio matrix transformations |
| App crashes on capture | Review ImageReader setup in logs |

### Log Output Example

```
D/CameraFragment: === CAMERA INITIALIZATION START ===
D/CameraFragment: Found 1 camera(s): [1000]
D/CameraFragment: Camera 1000 capabilities: [0]
D/CameraFragment: === SELECTED CAMERA ===
D/CameraFragment: Camera ID: 1000
D/CameraFragment: Lens facing: External
D/CameraFragment: Supports BACKWARD_COMPATIBLE: YES
```

## Required Modifications for Your App

1. **Update Package Names**: Change from `com.example.android.camera2.basic` to your app's package
2. **Navigation**: Update navigation directions to match your app's flow
3. **Permissions**: Ensure camera permissions are handled in your app
4. **Styling**: Update colors/themes to match your app design
5. **Dependencies**: Add camera dependencies to your existing `build.gradle`

## Next Steps

1. **Copy the files** listed in the integration guide
2. **Implement the `onPhotoCaptured()` function** for your app-specific logic
3. **Test on your target hardware** (Raspberry Pi CM4 + Emteria OS)
4. **Customize the UI** to match your app's design
5. **Add your processing pipeline** (AI, cloud services, etc.)

The camera foundation is ready - you just need to add your app-specific logic to the `onPhotoCaptured()` function!

## Community & Support

### Emteria Community

This implementation has been tested and optimized specifically for the Emteria ecosystem:
- Share your experiences in Emteria forums
- Report hardware-specific issues
- Contribute improvements back to the community

### Technical Support

For technical questions about this camera implementation:
- Check the debug logs first (`adb logcat | grep CameraFragment`)
- Review the Legacy HAL fixes in `CameraFragment.kt`
- Test camera discovery with different hardware configurations

### Contributing

Found a bug or have an improvement? 
- Test on different Emteria OS versions
- Try with different camera modules
- Submit issues with full debug logs
- Share successful hardware combinations

This implementation bridges the gap between Android Camera2 API and legacy HAL devices, making advanced camera functionality accessible on embedded Android platforms like Emteria OS.
