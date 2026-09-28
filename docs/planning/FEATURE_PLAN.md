> 上游历史文档，保留用于追溯；不代表当前拍照翻译版本。当前规则与实现见仓库根目录 `AGENTS.md` 和 `README.md`。

# 🚀 GlobalTranslation - Feature Roadmap

**Status**: Phases 1-2 Implemented - This Document Now Shows Future Enhancement Options  
**Implemented**: Phases 1-2 (Material 3 Theme + Camera Translation) ✅  
**Last Updated**: December 2024

## 📋 Executive Summary

This document originally outlined a 7-phase implementation plan. **Phases 1-2 have been implemented and are working in
production.** This document is preserved as a reference for potential future enhancements.

### ✅ Implemented Features (Production-Ready)

- ✅ **Live conversation mode** - Real-time speech-to-speech with Room persistence
- ✅ **Text input translation** - With history, TTS, clipboard integration
- ✅ **Camera translation** - Real-time OCR + translation (Phase 2 ✅)
- ✅ **Language model management** - Download/delete offline models
- ✅ **Material 3 expressive theme** - Lavender/purple palette (Phase 1 ✅)
- ✅ **Multi-module architecture** - :core, :data, :app with provider pattern
- ✅ **16KB page size support** - Google Play compliance
- ✅ **Material3 adaptive navigation** - NavigationSuiteScaffold

### 🔜 Future Enhancement Options (Not Currently Active)

The following phases were outlined as potential future work but are **not currently in active development**:

3. **Face-to-Face Conversation Mode (Split Screen)** - Not started
4. **AI-Powered Practice Conversations (Gemini)** - Not started
5. **Image Translation** - Not started
6. **Phrasebook / Saved Translations** - Not started
7. **Enhanced UI/UX with Promotional Cards** - Not started

---

## ✅ Phase 1: Material 3 Expressive Theme Redesign - **COMPLETED**

**Status**: ✅ **DELIVERED** (October 8, 2025)  
**Estimated Time**: 1-2 weeks ✅ Completed on schedule  
**Dependencies**: None

### Overview

Transform the app's design system to match Google Translate's expressive Material 3 style with large corner radii, soft
colors, and modern typography.

### Design Specifications

#### Color Palette

```kotlin
// New color scheme - Soft Lavender/Purple theme
private val LightLavender = Color(0xFFE8DEF8)
private val MediumPurple = Color(0xFF6750A4)
private val SoftWhite = Color(0xFFFFFBFE)
private val AccentPurple = Color(0xFF7F67BE)

// Apply to Material3Theme
MaterialTheme(
    colorScheme = lightColorScheme(
        primary = MediumPurple,
        primaryContainer = LightLavender,
        surface = SoftWhite,
        // ... additional colors
    )
)
```

#### Shape System - Large Corner Radii

```kotlin
// Create custom shapes with very large corner radii
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// Pill-shaped buttons
Button(
    shape = RoundedCornerShape(50), // Full pill shape
    // ...
)
```

#### Typography - Google Sans Style

```kotlin
val ExpressiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    // ... additional text styles
)
```

### Implementation Checklist

- [ ] Create new `ExpressiveTheme.kt` in `ui/theme/`
- [ ] Define lavender/purple color palette
- [ ] Configure large corner radius shapes
- [ ] Update all Card, Button, TextField components
- [ ] Add generous spacing (16-24dp padding)
- [ ] Implement Material Symbols outlined icons
- [ ] Test on different screen sizes

### Files to Create/Modify

- `app/src/main/java/com/example/globaltranslation/ui/theme/ExpressiveTheme.kt` (new)
- `app/src/main/java/com/example/globaltranslation/ui/theme/Color.kt` (update)
- `app/src/main/java/com/example/globaltranslation/ui/theme/Shape.kt` (new)
- All screen files for consistent styling

---

## ✅ Phase 2: Camera Translation (AR Overlay) - **COMPLETED**

**Status**: ✅ **DELIVERED** (October 8-9, 2025)  
**Estimated Time**: 3-4 weeks ✅ Completed ahead of schedule  
**Dependencies**: Material 3 theme ✅, CameraX ✅, ML Kit Text Recognition v2 ✅

### Overview

Implement real-time camera translation with augmented reality overlay, allowing users to point their camera at text and
see instant translations.

### Technical Stack

#### Required Dependencies

```kotlin
// gradle/libs.versions.toml
[versions]
camerax = "1.3.1"
mlkit-text-recognition = "16.0.0"

[libraries]
# CameraX
androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }

# ML Kit Text Recognition v2
mlkit-text-recognition = { module = "com.google.mlkit:text-recognition", version.ref = "mlkit-text-recognition" }
```

#### Architecture Pattern

```
ui/camera/
├── CameraScreen.kt              # Main camera UI
├── CameraViewModel.kt           # State management
├── CameraPreviewView.kt         # Custom PreviewView wrapper
├── TranslationOverlay.kt        # Canvas for drawing translated text
└── TextBlock.kt                 # Data class for detected text

services/
├── TextRecognitionService.kt    # ML Kit text recognition
└── CameraTranslationService.kt  # Combines OCR + Translation
```

### Features to Implement

#### 1. Camera Preview

```kotlin
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Box(modifier = modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    viewModel.startCamera(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Translation overlay
        TranslationOverlay(
            textBlocks = uiState.detectedTextBlocks,
            modifier = Modifier.fillMaxSize()
        )
        
        // Controls
        CameraControls(
            onCapture = viewModel::captureImage,
            onToggleFlash = viewModel::toggleFlash,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        )
    }
}
```

#### 2. Text Recognition Service

```kotlin
@Singleton
class TextRecognitionService @Inject constructor() {
    
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun recognizeText(image: InputImage): Result<Text> {
        return try {
            val result = recognizer.process(image).await()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun cleanup() {
        recognizer.close()
    }
}
```

#### 3. Real-Time Translation Overlay

```kotlin
@Composable
fun TranslationOverlay(
    textBlocks: List<TranslatedTextBlock>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        textBlocks.forEach { block ->
            // Draw semi-transparent background
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = block.boundingBox.topLeft,
                size = block.boundingBox.size,
                cornerRadius = CornerRadius(8.dp.toPx())
            )
            
            // Draw translated text
            drawContext.canvas.nativeCanvas.drawText(
                block.translatedText,
                block.boundingBox.left,
                block.boundingBox.top + block.textSize,
                textPaint
            )
        }
    }
}
```

### Implementation Checklist

- [ ] Add CameraX dependencies
- [ ] Add ML Kit Text Recognition v2
- [ ] Request CAMERA permission in manifest
- [ ] Create CameraScreen with PreviewView
- [ ] Implement TextRecognitionService
- [ ] Create CameraViewModel with image analysis
- [ ] Build translation overlay Canvas
- [ ] Add freeze-frame capture mode
- [ ] Implement flash toggle
- [ ] Add language selector in camera view
- [ ] Handle text orientation/rotation
- [ ] Add loading indicators
- [ ] Implement error handling
- [ ] Add "Tap to translate" functionality
- [ ] Create settings for overlay opacity

### UI/UX Considerations

- **Real-time vs Freeze-frame**: Offer both continuous scanning and single-shot modes
- **Performance**: Limit translation frequency (e.g., every 500ms) to avoid lag
- **Text Selection**: Allow users to tap specific text blocks for translation
- **Copy Functionality**: Long-press on translated text to copy

---

## 🔜 Phase 3: Face-to-Face Conversation Mode (Split Screen)

**Status**: 🔜 **FUTURE ENHANCEMENT** (Not Currently Planned)  
**Priority**: Would be HIGH if development resumes  
**Estimated Time**: 2 weeks  
**Dependencies**: Current conversation mode ✅, Material 3 theme ✅

### Overview

Potential future enhancement to add a split-screen interface where translations appear upside-down for the person
opposite, enabling natural face-to-face conversations.

### Architecture Pattern

```
ui/conversation/
├── FaceToFaceModeScreen.kt      # Split screen layout
├── FaceToFaceModeViewModel.kt   # Enhanced conversation state
└── RotatedTranslationCard.kt    # Upside-down text display
```

### Features to Implement

#### 1. Split Screen Layout

```kotlin
@Composable
fun FaceToFaceModeScreen(
    modifier: Modifier = Modifier,
    viewModel: FaceToFaceModeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Column(modifier = modifier.fillMaxSize()) {
        // Top half - rotated for person opposite (180 degrees)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .rotate(180f)
        ) {
            TranslationDisplay(
                text = uiState.lastTranslation?.translatedText ?: "",
                language = uiState.targetLanguage,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        
        HorizontalDivider(thickness = 2.dp)
        
        // Bottom half - normal orientation for current user
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            TranslationDisplay(
                text = uiState.lastTranslation?.originalText ?: "",
                language = uiState.sourceLanguage,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        
        // Centered microphone button
        FloatingActionButton(
            onClick = viewModel::toggleListening,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .offset(y = (-32).dp)
        ) {
            Icon(
                if (uiState.getListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Toggle listening"
            )
        }
    }
}
```

#### 2. Auto Language Detection

```kotlin
@HiltViewModel
class FaceToFaceModeViewModel @Inject constructor(
    private val speechService: SpeechRecognitionService,
    private val translationService: TranslationService
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(FaceToFaceUiState())
    val uiState: StateFlow<FaceToFaceUiState> = _uiState.asStateFlow()
    
    fun toggleListening() {
        viewModelScope.launch {
            if (_uiState.value.getListening) {
                stopListening()
            } else {
                startListening()
            }
        }
    }
    
    private suspend fun startListening() {
        _uiState.value = _uiState.value.copy(getListening = true)
        
        speechService.listen(Locale.getDefault()).collect { event ->
            when (event) {
                is SpeechEvent.PartialResult -> {
                    // Detect which language is being spoken
                    val detectedLanguage = detectLanguage(event.text)
                    val isSourceLanguage = detectedLanguage == _uiState.value.sourceLanguage
                    
                    translateAndDisplay(event.text, isSourceLanguage)
                }
                // ... handle other events
            }
        }
    }
    
    private suspend fun detectLanguage(text: String): String {
        // Use ML Kit Language Identification
        // Return detected language code
    }
}
```

### Implementation Checklist

- [ ] Create FaceToFaceModeScreen with split layout
- [ ] Implement 180-degree rotation for top half
- [ ] Add auto language detection
- [ ] Create smooth transition between speakers
- [ ] Add visual indicator for active speaker
- [ ] Implement "Auto language detection is on" popup
- [ ] Create settings bottom sheet:
    - [ ] Text size slider
    - [ ] Auto-playback toggle
    - [ ] Manual language switch
- [ ] Add microphone pulsating animation
- [ ] Handle orientation changes gracefully
- [ ] Add consent dialog for audio processing
- [ ] Implement history view (swipe gesture)

---

## 🔜 Phase 4: AI-Powered Practice Conversations (Gemini)

**Status**: 🔜 **FUTURE ENHANCEMENT** (Not Currently Planned)  
**Priority**: Would be MEDIUM if development resumes  
**Estimated Time**: 4-5 weeks  
**Dependencies**: Gemini API integration, user authentication (optional)

### Overview

Potential future enhancement for AI language learning where users can practice conversations with Gemini AI in their
target language, with personalized scenarios and progress tracking.

### Technical Stack

#### Required Dependencies

```kotlin
// gradle/libs.versions.toml
[versions]
generativeai = "0.9.0"

[libraries]
google-generativeai = { module = "com.google.ai.client.generativeai:generativeai", version.ref = "generativeai" }
```

### Architecture Pattern

```
ui/practice/
├── PracticeOnboardingFlow.kt    # Multi-step setup
├── PracticeHomeScreen.kt         # Dashboard with scenarios
├── PracticeConversationScreen.kt # Chat interface with AI
├── PracticeViewModel.kt          # State management
└── components/
    ├── ProgressCard.kt           # Daily goals, stars
    ├── ScenarioCard.kt           # Recommended scenarios
    └── CustomScenarioDialog.kt   # Create custom practice

services/
├── GeminiService.kt              # Gemini API integration
└── PracticeStorageService.kt     # Save progress, scenarios

data/
└── PracticeRepository.kt         # Room database for offline storage
```

### Features to Implement

#### 1. Onboarding Flow

```kotlin
@Composable
fun PracticeOnboardingFlow(
    onComplete: (PracticeProfile) -> Unit
) {
    var currentStep by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf(PracticeProfile()) }
    
    when (currentStep) {
        0 -> WelcomeScreen(
            onNext = { currentStep++ }
        )
        1 -> LanguageSelectionScreen(
            onLanguageSelected = { lang ->
                profile = profile.copy(targetLanguage = lang)
                currentStep++
            }
        )
        2 -> ProficiencyLevelScreen(
            selectedLevel = profile.proficiencyLevel,
            onLevelSelected = { level ->
                profile = profile.copy(proficiencyLevel = level)
                currentStep++
            }
        )
        3 -> GoalSelectionScreen(
            selectedGoals = profile.goals,
            onGoalsSelected = { goals ->
                profile = profile.copy(goals = goals)
                onComplete(profile)
            }
        )
    }
}

data class PracticeProfile(
    val targetLanguage: String = "",
    val proficiencyLevel: ProficiencyLevel = ProficiencyLevel.BASIC,
    val goals: List<LearningGoal> = emptyList()
)

enum class ProficiencyLevel {
    BASIC, INTERMEDIATE, ADVANCED
}

enum class LearningGoal {
    TRAVEL, JOB, FRIENDS, EDUCATION, HOBBY
}
```

#### 2. Gemini Service Integration

```kotlin
@Singleton
class GeminiService @Inject constructor() {
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-pro",
        apiKey = BuildConfig.GEMINI_API_KEY
    )
    
    suspend fun generateScenario(
        targetLanguage: String,
        proficiency: ProficiencyLevel,
        goals: List<LearningGoal>
    ): Result<PracticeScenario> {
        val prompt = """
            Generate a language learning conversation scenario for:
            - Target language: $targetLanguage
            - Proficiency level: $proficiency
            - Learning goals: ${goals.joinToString()}
            
            Format: JSON with title, description, and initial AI message
        """.trimIndent()
        
        return try {
            val response = generativeModel.generateContent(prompt)
            val scenario = parseScenarioFromResponse(response.text)
            Result.success(scenario)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun chat(
        conversationHistory: List<ChatMessage>,
        userMessage: String,
        scenario: PracticeScenario
    ): Flow<String> = flow {
        val chat = generativeModel.startChat(
            history = conversationHistory.map { msg ->
                content(msg.role) { text(msg.content) }
            }
        )
        
        chat.sendMessageStream(userMessage).collect { chunk ->
            emit(chunk.text ?: "")
        }
    }
}

data class PracticeScenario(
    val id: String,
    val title: String,
    val description: String,
    val difficulty: ProficiencyLevel,
    val category: LearningGoal,
    val systemPrompt: String
)
```

#### 3. Practice Chat Interface

```kotlin
@Composable
fun PracticeConversationScreen(
    scenario: PracticeScenario,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scenario.title) },
                actions = {
                    IconButton(onClick = { /* Show hints */ }) {
                        Icon(Icons.Default.Lightbulb, "Hints")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Chat messages
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true
            ) {
                items(uiState.messages.reversed()) { message ->
                    ChatBubble(
                        message = message,
                        onTranslate = viewModel::translateMessage,
                        onSpeak = viewModel::speakMessage
                    )
                }
            }
            
            // Input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.currentInput,
                    onValueChange = viewModel::updateInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type your message...") },
                    trailingIcon = {
                        IconButton(onClick = viewModel::toggleVoiceInput) {
                            Icon(Icons.Default.Mic, "Voice input")
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = viewModel::sendMessage,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
```

#### 4. Progress Tracking Dashboard

```kotlin
@Composable
fun PracticeHomeScreen(
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Daily progress card
        item {
            ProgressCard(
                dailyGoal = uiState.dailyGoal,
                starsEarned = uiState.starsToday,
                newWordsPracticed = uiState.newWordsToday,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Quick actions
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Continue practicing",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { /* Create custom */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create custom scenario")
                    }
                }
            }
        }
        
        // Recommended scenarios
        item {
            Text(
                "Recommended for you",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(uiState.recommendedScenarios) { scenario ->
            ScenarioCard(
                scenario = scenario,
                onClick = { viewModel.startScenario(scenario) }
            )
        }
    }
}
```

### Implementation Checklist

- [ ] Add Gemini API dependency
- [ ] Create API key configuration (BuildConfig)
- [ ] Implement onboarding flow:
    - [ ] Welcome screen
    - [ ] Language selection
    - [ ] Proficiency level selection
    - [ ] Goal selection
- [ ] Build GeminiService for chat
- [ ] Create scenario generation logic
- [ ] Implement chat interface
- [ ] Add message translation feature
- [ ] Build progress tracking:
    - [ ] Daily goals
    - [ ] Star system
    - [ ] New words counter
- [ ] Create Room database for:
    - [ ] User profile
    - [ ] Practice history
    - [ ] Custom scenarios
- [ ] Implement custom scenario creation
- [ ] Add hints/suggestions system
- [ ] Create feedback mechanism
- [ ] Add loading animations
- [ ] Implement error handling
- [ ] Add consent dialog for AI usage
- [ ] Create beta badge/indicator

---

## 🔜 Phase 5: Image Translation

**Status**: 🔜 **FUTURE ENHANCEMENT** (Not Currently Planned)  
**Priority**: Would be MEDIUM if development resumes  
**Estimated Time**: 2 weeks  
**Dependencies**: Camera translation infrastructure ✅

### Overview

Potential future enhancement to allow users to upload or capture images and translate all text within them, with the
option to save or share translated images.

### Architecture Pattern

```
ui/image/
├── ImageTranslationScreen.kt    # Image picker + result
├── ImageTranslationViewModel.kt # OCR + translation
└── TranslatedImageView.kt       # Display with overlays

services/
└── ImageTranslationService.kt   # Combines OCR + Translation + Image manipulation
```

### Features to Implement

#### 1. Image Picker & Capture

```kotlin
@Composable
fun ImageTranslationScreen(
    modifier: Modifier = Modifier,
    viewModel: ImageTranslationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processImage(it) }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.processedImage != null) {
            // Show translated image
            TranslatedImageView(
                image = uiState.processedImage,
                textBlocks = uiState.translatedBlocks,
                onSave = viewModel::saveImage,
                onShare = viewModel::shareImage
            )
        } else {
            // Image selection UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = { launcher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoLibrary, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose from gallery")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = viewModel::captureImage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take photo")
                }
            }
        }
    }
}
```

#### 2. Image Translation Service

```kotlin
@Singleton
class ImageTranslationService @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    private val translationService: TranslationService
) {
    
    suspend fun translateImage(
        imageUri: Uri,
        fromLanguage: String,
        toLanguage: String
    ): Result<TranslatedImageData> {
        return try {
            // 1. Load image
            val bitmap = loadBitmap(imageUri)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            // 2. Recognize text
            val ocrResult = textRecognitionService.recognizeText(inputImage)
                .getOrThrow()
            
            // 3. Translate each text block
            val translatedBlocks = ocrResult.textBlocks.map { block ->
                val translation = translationService.translate(
                    text = block.text,
                    fromLanguage = fromLanguage,
                    toLanguage = toLanguage
                ).getOrThrow()
                
                TranslatedTextBlock(
                    originalText = block.text,
                    translatedText = translation,
                    boundingBox = block.boundingBox,
                    confidence = block.confidence
                )
            }
            
            Result.success(
                TranslatedImageData(
                    originalBitmap = bitmap,
                    translatedBlocks = translatedBlocks
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun createTranslatedImage(
        data: TranslatedImageData
    ): Result<Bitmap> {
        return try {
            val mutableBitmap = data.originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            
            data.translatedBlocks.forEach { block ->
                // Draw white background behind translated text
                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    alpha = 230
                }
                canvas.drawRect(block.boundingBox, paint)
                
                // Draw translated text
                val textPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = calculateOptimalTextSize(block.boundingBox)
                    isAntiAlias = true
                }
                canvas.drawText(
                    block.translatedText,
                    block.boundingBox.left,
                    block.boundingBox.centerY(),
                    textPaint
                )
            }
            
            Result.success(mutableBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### Implementation Checklist

- [ ] Create ImageTranslationScreen
- [ ] Add image picker (gallery)
- [ ] Add camera capture option
- [ ] Implement ImageTranslationService
- [ ] Process image with OCR
- [ ] Translate detected text blocks
- [ ] Create translated image overlay
- [ ] Add save to gallery functionality
- [ ] Add share functionality
- [ ] Implement zoom/pan for large images
- [ ] Add loading indicators
- [ ] Handle large images efficiently
- [ ] Add option to edit translations manually
- [ ] Create before/after comparison view

---

## 🔜 Phase 6: Phrasebook / Saved Translations

**Status**: 🔜 **FUTURE ENHANCEMENT** (Not Currently Planned)  
**Priority**: Would be LOW if development resumes  
**Estimated Time**: 1-2 weeks  
**Dependencies**: Room database ✅ (already available)

### Overview

Potential future enhancement to allow users to save frequently used translations for quick access, organized by
categories with search and sync capabilities.

### Technical Stack

#### Required Dependencies

```kotlin
// gradle/libs.versions.toml
[versions]
room = "2.6.1"

[libraries]
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
```

### Architecture Pattern

```
ui/phrasebook/
├── PhrasebookScreen.kt          # Main phrasebook view
├── PhrasebookViewModel.kt       # State management
├── PhraseCategoryDialog.kt      # Create/edit categories
└── PhraseDetailSheet.kt         # View/edit saved phrase

data/
├── database/
│   ├── PhrasebookDatabase.kt    # Room database
│   ├── SavedPhrase.kt           # Entity
│   ├── PhraseCategory.kt        # Entity
│   └── PhrasebookDao.kt         # Data access object
└── repository/
    └── PhrasebookRepository.kt  # Business logic
```

### Features to Implement

#### 1. Database Schema

```kotlin
@Entity(tableName = "saved_phrases")
data class SavedPhrase(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val categoryId: Long? = null,
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val usageCount: Int = 0
)

@Entity(tableName = "phrase_categories")
data class PhraseCategory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val color: Int? = null
)

@Dao
interface PhrasebookDao {
    @Query("SELECT * FROM saved_phrases ORDER BY timestamp DESC")
    fun getAllPhrases(): Flow<List<SavedPhrase>>
    
    @Query("SELECT * FROM saved_phrases WHERE categoryId = :categoryId")
    fun getPhrasesByCategory(categoryId: Long): Flow<List<SavedPhrase>>
    
    @Query("SELECT * FROM saved_phrases WHERE originalText LIKE '%' || :query || '%' OR translatedText LIKE '%' || :query || '%'")
    fun searchPhrases(query: String): Flow<List<SavedPhrase>>
    
    @Insert
    suspend fun insertPhrase(phrase: SavedPhrase): Long
    
    @Update
    suspend fun updatePhrase(phrase: SavedPhrase)
    
    @Delete
    suspend fun deletePhrase(phrase: SavedPhrase)
    
    @Query("SELECT * FROM phrase_categories ORDER BY name")
    fun getAllCategories(): Flow<List<PhraseCategory>>
}
```

#### 2. Phrasebook UI

```kotlin
@Composable
fun PhrasebookScreen(
    modifier: Modifier = Modifier,
    viewModel: PhrasebookViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phrasebook") },
                actions = {
                    IconButton(onClick = { /* Create category */ }) {
                        Icon(Icons.Default.CreateNewFolder, "New category")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { viewModel.search(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            
            // Category chips
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedCategory == null,
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text("All") }
                    )
                }
                items(uiState.categories) { category ->
                    FilterChip(
                        selected = uiState.selectedCategory == category.id,
                        onClick = { viewModel.selectCategory(category.id) },
                        label = { Text(category.name) }
                    )
                }
            }
            
            // Phrases list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.phrases) { phrase ->
                    SavedPhraseCard(
                        phrase = phrase,
                        onClick = { viewModel.selectPhrase(phrase) },
                        onSpeak = { viewModel.speakPhrase(phrase) },
                        onCopy = { viewModel.copyPhrase(phrase) },
                        onDelete = { viewModel.deletePhrase(phrase) }
                    )
                }
            }
        }
    }
}
```

#### 3. Quick Save Feature

```kotlin
// Add to translation result screens
@Composable
fun TranslationResultCard(
    translation: TextTranslation,
    onSave: () -> Unit,
    // ... other callbacks
) {
    Card {
        Column {
            // ... translation display
            
            Row {
                // ... existing buttons
                
                IconButton(onClick = onSave) {
                    Icon(Icons.Default.BookmarkAdd, "Save to phrasebook")
                }
            }
        }
    }
}
```

### Implementation Checklist

- [ ] Add Room dependencies
- [ ] Create database entities (SavedPhrase, PhraseCategory)
- [ ] Build PhrasebookDao
- [ ] Implement PhrasebookRepository
- [ ] Create PhrasebookScreen with search
- [ ] Add category management (create, edit, delete)
- [ ] Implement phrase CRUD operations
- [ ] Add "Save to phrasebook" button to all translation screens
- [ ] Create phrase detail bottom sheet
- [ ] Add export/import functionality (JSON)
- [ ] Implement backup/restore
- [ ] Add sorting options (date, usage, alphabetical)
- [ ] Create widget for quick access
- [ ] Add usage statistics

---

## 🔜 Phase 7: UI/UX Enhancements

**Status**: ⚠️ **PARTIALLY IMPLEMENTED** (Some features already in production)  
**Priority**: LOW (most impactful features already implemented)  
**Estimated Time**: 1 week (for remaining features)  
**Dependencies**: Material 3 theme ✅

### Overview

UI/UX enhancement phase with some features already implemented and others remaining as future enhancements. The app
currently includes advanced Material 3 components and patterns not originally planned.

### ✅ Already Implemented (Not Previously Documented)

- **Material 3 HorizontalCenteredHeroCarousel** (LanguageScreen) - Compose BOM 2025.10.00 feature
- **Pull-to-refresh pattern** (ConversationScreen) - Access saved Room history
- **Network status indicators** (LanguageScreen) - WiFi/Cellular/Offline monitoring
- **Basic scroll animations** (ConversationScreen) - Auto-scroll to latest message

### 🔜 Remaining Phase 7 Features

Potential future enhancements to further polish the user experience:

### Features to Implement

#### 1. Promotional/Discovery Cards

```kotlin
@Composable
fun PromotionalCard(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    TextButton(onClick = onAction) {
                        Text(action)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Not now")
                    }
                }
            }
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
```

#### 2. Smooth Animations

```kotlin
// Page transitions
val pageTransition = slideIntoContainer(
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
    animationSpec = tween(300, easing = EaseInOutCubic)
)

// Loading shimmer effect
@Composable
fun ShimmerLoadingCard() {
    val transition = rememberInfiniteTransition()
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Restart
        )
    )
    
    // ... shimmer implementation
}

// Microphone pulse animation
@Composable
fun PulsingMicrophone(getListening: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (getListening) 1.2f else 1f,
        animationSpec = tween(300)
    )
    
    Icon(
        Icons.Default.Mic,
        contentDescription = "Microphone",
        modifier = Modifier
            .scale(scale)
            .graphicsLayer {
                if (getListening) {
                    alpha = 0.5f + (scale - 1f) * 2.5f
                }
            }
    )
}
```

#### 3. Paste Chip Button

```kotlin
@Composable
fun PasteChip(
    onPaste: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    AssistChip(
        onClick = {
            val text = clipboardManager.getText()?.text
            if (text != null) {
                onPaste(text)
            } else {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        },
        label = { Text("Paste") },
        leadingIcon = {
            Icon(Icons.Default.ContentPaste, contentDescription = null)
        },
        modifier = modifier
    )
}
```

### Implementation Checklist

**Already Complete** ✅:

- [x] Material 3 carousel component (LanguageScreen)
- [x] Network status monitoring (LanguageScreen)
- [x] Pull-to-refresh pattern (ConversationScreen)
- [x] Basic scroll animations (ConversationScreen)

**Quick Wins** ⚡ (Easy to implement):

- [ ] Add Paste chip to text input (1-2 hours, high impact)
- [ ] Implement microphone pulse animation (2-3 hours, medium impact)
- [ ] Add empty states with illustrations (3-4 hours, high impact)

**Medium Effort** 📝:

- [ ] Add loading skeletons/shimmers (4-6 hours)
- [ ] Create smooth page transitions (4-6 hours)
- [ ] Add haptic feedback for key actions (4-6 hours)
- [ ] Implement swipe gestures for history (4-6 hours)

**Lower Priority** 📌:

- [ ] Add promotional card to main screen (6-8 hours)
- [ ] Implement card dismissal persistence (2-3 hours)
- [ ] Create bottom sheet animations (4-6 hours)
- [ ] Create onboarding tooltips (6-8 hours)
- [ ] Add app shortcuts (Android 7.1+) (4-6 hours)

---

## 📦 Dependencies Summary

### New Dependencies to Add

```toml
# gradle/libs.versions.toml

[versions]
camerax = "1.3.1"
mlkit-text-recognition = "16.0.0"
generativeai = "0.9.0"
room = "2.6.1"

[libraries]
# CameraX
androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }

# ML Kit
mlkit-text-recognition = { module = "com.google.mlkit:text-recognition", version.ref = "mlkit-text-recognition" }

# Gemini AI
google-generativeai = { module = "com.google.ai.client.generativeai:generativeai", version.ref = "generativeai" }

# Room Database
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
```

### Required Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

---

## 🗓️ Implementation Timeline

### ✅ Completed Phases (October 2025)

- ✅ **Phase 1: Material 3 Theme** - Completed October 8, 2025
- ✅ **Phase 2: Camera Translation** - Completed October 8-9, 2025
- ✅ **Architecture Refactoring** - Completed October 10, 2025
- ✅ **ViewModel Migration** - Completed October 10, 2025
- ✅ **16KB Page Size Support** - Completed December 2024

**Total Development Time: ~2 weeks** (significantly ahead of original 19-week estimate)

### 🔜 Future Enhancement Timeline (If Development Resumes)

The following phases were originally planned but are not currently in development:

- Phase 3: Face-to-Face Mode (2 weeks estimated)
- Phase 4: AI Practice (4-5 weeks estimated)
- Phase 5: Image Translation (2 weeks estimated)
- Phase 6: Phrasebook (1-2 weeks estimated)
- Phase 7: UI/UX Polish (1-2 weeks estimated)

**Note**: The above timeline is preserved for reference only. The app is currently feature-complete for its core
translation capabilities.

---

## 🎯 Success Metrics

### User Engagement

- Daily active users increase by 50%
- Average session duration increase by 3x
- Feature adoption rate > 40% within 3 months

### Technical Performance

- Camera translation < 1s latency
- OCR accuracy > 95%
- AI response time < 2s
- App crash rate < 0.1%

### User Satisfaction

- App store rating > 4.5 stars
- Feature satisfaction score > 85%
- Net Promoter Score (NPS) > 50

---

## 🔒 Security & Privacy Considerations

### Data Handling

- **Camera/Images**: Process locally when possible, clear consent for cloud processing
- **Voice Data**: Comply with GDPR/CCPA, provide data deletion options
- **AI Conversations**: Encrypt stored conversations, option to disable history
- **Phrasebook Sync**: End-to-end encryption for cloud sync

### API Keys

- Store Gemini API key securely (BuildConfig, not in VCS)
- Implement rate limiting
- Use ProGuard/R8 for release builds
- Add API key rotation mechanism

### Permissions

- Request permissions just-in-time (when feature is used)
- Provide clear explanations for each permission
- Gracefully handle permission denials
- Allow users to review and revoke permissions in settings

---

## 📝 Current Status & Next Steps

### ✅ Planned Features Implemented

All infrastructure, dependencies, and planned core features have been implemented:

- ✅ Build configuration stable (AGP 8.13.0, Kotlin 2.2.20)
- ✅ Multi-module architecture in place (:core, :data, :app)
- ✅ All dependencies configured (CameraX, ML Kit, Room, Hilt)
- ✅ Material 3 theme implemented
- ✅ All core screens operational
- ✅ Test infrastructure ready

### 🔜 If Resuming Future Development

Should development resume for Phases 3-7:

1. Review and prioritize remaining phases based on user feedback
2. Create detailed implementation tickets for chosen phase
3. Design mockups for new features in Figma
4. Implement following existing architecture patterns
5. Test and iterate with users

---

**Document Version**: 2.0  
**Created**: October 8, 2025  
**Updated**: December 2024 (status change to reflect completion)  
**Author**: AI Assistant (based on user requirements)

