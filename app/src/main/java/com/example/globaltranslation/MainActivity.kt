package com.example.globaltranslation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.globaltranslation.ui.camera.CameraViewModel
import com.example.globaltranslation.ui.camera.PhotoTranslationApp
import com.example.globaltranslation.ui.theme.GlobalTranslationTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GlobalTranslationTheme { PhotoTranslationApp(viewModel) } }
    }
}
