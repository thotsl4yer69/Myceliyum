package com.example.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.example.model.UserSighting

/** Submission state survives rotation, so a completed save cannot leave a second active draft. */
class SightingSession : ViewModel() {
    val snackbar = androidx.compose.material3.SnackbarHostState()
    val showAdd = mutableStateOf(false)
    val selectedDetail = mutableStateOf<UserSighting?>(null)
    val editingId = mutableStateOf<Long?>(null)
    val saving = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val confirmDelete = mutableStateOf<UserSighting?>(null)
    val location = mutableStateOf<Pair<Double, Double>?>(null)
}
