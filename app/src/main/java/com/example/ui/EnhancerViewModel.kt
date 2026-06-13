package com.example.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import com.example.api.GeminiEnhanceBlueprint
import com.example.processing.ImageProcessor
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.util.Base64

sealed interface GeminiState {
    object Idle : GeminiState
    object Analyzing : GeminiState
    data class Success(val blueprint: GeminiEnhanceBlueprint) : GeminiState
    data class Error(val message: String) : GeminiState
}

class EnhancerViewModel : ViewModel() {

    // --- Core Bitmaps ---
    // The master original untouched loaded image
    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    // The bitmap with structural operations applied (crop, denoise, sharpen, high-res)
    private val _structuralBitmap = MutableStateFlow<Bitmap?>(null)
    val structuralBitmap: StateFlow<Bitmap?> = _structuralBitmap.asStateFlow()

    // History stack for Undo operations
    private val undoStack = mutableListOf<Bitmap>()
    
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    // --- Tuning Sliders (Real-time GPU) ---
    val brightness = MutableStateFlow(0f)      // -100 to 100
    val contrast = MutableStateFlow(0f)        // -100 to 100
    val saturation = MutableStateFlow(0f)      // -100 to 100
    val hue = MutableStateFlow(0f)             // -180 to 180

    // --- Structural Slider Controls (Applied on Apply click) ---
    val sharpness = MutableStateFlow(0f)       // 0 to 100 (maps to 0f..1.5f sharpen)
    val denoise = MutableStateFlow(0f)         // 0 to 100 (maps to 0f..0.8f blur)

    // --- UI/Operation state ---
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _geminiState = MutableStateFlow<GeminiState>(GeminiState.Idle)
    val geminiState: StateFlow<GeminiState> = _geminiState.asStateFlow()

    // --- Crop parameters (Normalised float coordinates 0f to 1f) ---
    val cropLeft = MutableStateFlow(0.0f)
    val cropTop = MutableStateFlow(0.0f)
    val cropRight = MutableStateFlow(1.0f)
    val cropBottom = MutableStateFlow(1.0f)
    
    val isCropModeActive = MutableStateFlow(false)

    // --- Initialise master photo ---
    fun setPhoto(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
        _structuralBitmap.value = bitmap
        undoStack.clear()
        _canUndo.value = false
        resetSliders()
    }

    private fun pushToUndo(bitmap: Bitmap) {
        undoStack.add(bitmap)
        if (undoStack.size > 8) {
            undoStack.removeAt(0)
        }
        _canUndo.value = true
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeLast()
            _structuralBitmap.value = prev
            _canUndo.value = undoStack.isNotEmpty()
            resetSliders()
        }
    }

    fun resetSliders() {
        brightness.value = 0f
        contrast.value = 0f
        saturation.value = 0f
        hue.value = 0f
    }

    fun resetToOriginal() {
        _originalBitmap.value?.let {
            _structuralBitmap.value = it
            undoStack.clear()
            _canUndo.value = false
            resetSliders()
            sharpness.value = 0f
            denoise.value = 0f
            cropLeft.value = 0.0f
            cropTop.value = 0.0f
            cropRight.value = 1.0f
            cropBottom.value = 1.0f
            isCropModeActive.value = false
            _geminiState.value = GeminiState.Idle
        }
    }

    // --- Advanced Operations (Executed in background) ---

    fun applyStructuralEdits() {
        val current = _structuralBitmap.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                pushToUndo(current)
                
                var workingBitmap = current
                
                // 1. Denoise (Smooth out pixels if slider > 0)
                if (denoise.value > 0f) {
                    val normDenoise = (denoise.value / 100f) * 0.8f
                    workingBitmap = ImageProcessor.denoise(workingBitmap, normDenoise)
                }
                
                // 2. Sharpen (Enhance details / deblur if slider > 0)
                if (sharpness.value > 0f) {
                    val normSharpen = (sharpness.value / 100f) * 1.5f
                    workingBitmap = ImageProcessor.sharpen(workingBitmap, normSharpen)
                }
                
                _structuralBitmap.value = workingBitmap
                
                // Reset structural adjust sliders after application so they aren't double applied
                sharpness.value = 0f
                denoise.value = 0f
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun applyCrop() {
        val current = _structuralBitmap.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                pushToUndo(current)
                val cropped = ImageProcessor.crop(
                    src = current,
                    left = cropLeft.value,
                    top = cropTop.value,
                    right = cropRight.value,
                    bottom = cropBottom.value
                )
                _structuralBitmap.value = cropped
                
                // Reset crop layout
                cropLeft.value = 0.0f
                cropTop.value = 0.0f
                cropRight.value = 1.0f
                cropBottom.value = 1.0f
                isCropModeActive.value = false
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun applyUpscale(factor: Float) {
        val current = _structuralBitmap.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                pushToUndo(current)
                val scaled = ImageProcessor.upscale(current, factor)
                _structuralBitmap.value = scaled
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // --- Gemini AI Analysis & Smart Fine-Tuner ---
    fun runGeminiSmartEnhance() {
        val current = _structuralBitmap.value ?: return
        _geminiState.value = GeminiState.Analyzing
        
        viewModelScope.launch {
            try {
                // Convert bitmap to Base64 JPEG
                val base64Data = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val outputStream = ByteArrayOutputStream()
                    // Compress to 70 quality and limit size to optimize network payload
                    val scaleWidth = if (current.width > 800) 800 else current.width
                    val scaleHeight = (scaleWidth.toFloat() / current.width * current.height).toInt()
                    val resized = Bitmap.createScaledBitmap(current, scaleWidth, scaleHeight, true)
                    resized.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                    Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                }

                val systemPrompt = """
                    You are an expert digital art restorer, photography retoucher and image enhancement AI engine. Your job is to analyze the user's uploaded image and write an optimal image-enhancement blueprint in JSON format.
                    
                    Analyze the composition, colors, depth, and identifying elements (e.g. if the image contains high-relief 3D floral art on wood, ornate metal handles, decorative boards, etc.). Check the focus (is it blurry?), noise level, and color contrast.
                    
                    You MUST output a single, valid JSON block matching this schema EXACTLY:
                    {
                      "artworkTitle": "The descriptive title of the artwork",
                      "analysis": "Artistic analysis of composition, color tones, depth, pattern, and current photo quality (e.g., has blur, borders, noise, low resolution)",
                      "suggestedContrast": integer between -100 and 100,
                      "suggestedBrightness": integer between -100 and 100,
                      "suggestedSaturation": integer between -100 and 100,
                      "recommendedSharpness": integer between 0 and 100,
                      "recommendedDenoise": integer between 0 and 100,
                      "restorationInsight": "A step-by-step recommendation on how the user can crop, sharpen, de-noise, and upscale to perfectly extract and showcase this masterpiece"
                    }
                    
                    Make your suggested sliders align with professional photo enhancement (e.g., if colors are dark and metallic, boost saturation and contrast to let red/gold lacquer shine; if blurry, recommend higher sharpness).
                    Output RAW JSON text only. Do not contain any markdown blocks (like ```json).
                """.trimIndent()

                val apiRequest = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = "Analyze this image and generate the enhancement blueprint JSON according to the schema."),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.2f
                    ),
                    systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
                )

                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    _geminiState.value = GeminiState.Error("API Key is missing. Please configure GEMINI_API_KEY in the AI Studio Secrets panel!")
                    return@launch
                }

                val response = RetrofitClient.service.generateContent(apiKey, apiRequest)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw Exception("Empty response from AI model")

                // Clean response string just in case
                val cleanJson = responseText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val adapter: JsonAdapter<GeminiEnhanceBlueprint> = RetrofitClient.moshiInstance.adapter(GeminiEnhanceBlueprint::class.java)
                val blueprint = adapter.fromJson(cleanJson) ?: throw Exception("Failed to parse JSON response model")

                _geminiState.value = GeminiState.Success(blueprint)

                // Auto-apply values to sliders for user review
                brightness.value = blueprint.suggestedBrightness.toFloat()
                contrast.value = blueprint.suggestedContrast.toFloat()
                saturation.value = blueprint.suggestedSaturation.toFloat()
                sharpness.value = blueprint.recommendedSharpness.toFloat()
                denoise.value = blueprint.recommendedDenoise.toFloat()

            } catch (e: Exception) {
                e.printStackTrace()
                _geminiState.value = GeminiState.Error("API Call failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }
}
