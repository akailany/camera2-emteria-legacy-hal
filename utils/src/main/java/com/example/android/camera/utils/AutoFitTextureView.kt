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

package com.example.android.camera.utils

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import kotlin.math.roundToInt

/**
 * A [TextureView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 * This version supports matrix transformations for preview rotation.
 */
class AutoFitTextureView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var aspectRatio = 0f
    private var cameraWidth = 0
    private var cameraHeight = 0

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        
        aspectRatio = width.toFloat() / height.toFloat()
        cameraWidth = width
        cameraHeight = height
        
        Log.d(TAG, "=== SETTING ASPECT RATIO ===")
        Log.d(TAG, "Camera resolution: ${width} x ${height}")
        Log.d(TAG, "Calculated aspect ratio: $aspectRatio")
        
        // Try to set buffer size now if surface texture is available
        updateSurfaceTextureBufferSize()
        
        requestLayout()
    }
    
    /**
     * Updates the surface texture buffer size when both camera dimensions and surface texture are available
     */
    private fun updateSurfaceTextureBufferSize() {
        if (cameraWidth > 0 && cameraHeight > 0 && surfaceTexture != null) {
            surfaceTexture?.setDefaultBufferSize(cameraWidth, cameraHeight)
            Log.d(TAG, "✅ SurfaceTexture buffer size set to: ${cameraWidth} x ${cameraHeight}")
        } else {
            Log.d(TAG, "⏳ Waiting for surface texture or camera dimensions (width=$cameraWidth, height=$cameraHeight, surfaceTexture=${surfaceTexture != null})")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        Log.d(TAG, "=== onMeasure called ===")
        Log.d(TAG, "Available space: ${width} x ${height}")
        Log.d(TAG, "Current aspect ratio: $aspectRatio")
        
        if (aspectRatio == 0f) {
            Log.d(TAG, "No aspect ratio set, using available space")
            setMeasuredDimension(width, height)
        } else {

            // Performs center-crop transformation of the camera frames
            val newWidth: Int
            val newHeight: Int
            val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
            
            Log.d(TAG, "Landscape mode: ${width > height}")
            Log.d(TAG, "Actual ratio to use: $actualRatio")
            
            if (width < height * actualRatio) {
                newHeight = height
                newWidth = (height * actualRatio).roundToInt()
                Log.d(TAG, "Width limited: keeping height=$newHeight, calculating width=$newWidth")
            } else {
                newWidth = width
                newHeight = (width / actualRatio).roundToInt()
                Log.d(TAG, "Height limited: keeping width=$newWidth, calculating height=$newHeight")
            }

            Log.d(TAG, "Final measured dimensions: $newWidth x $newHeight")
            setMeasuredDimension(newWidth, newHeight)
        }
    }

    companion object {
        private val TAG = AutoFitTextureView::class.java.simpleName
    }
} 