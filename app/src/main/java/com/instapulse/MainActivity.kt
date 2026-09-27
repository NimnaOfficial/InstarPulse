package com.instapulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import com.instapulse.ui.InstaPulseViewModel
import com.instapulse.ui.dashboard.InstaPulseDashboardScreen
import com.instapulse.ui.login.InstagramLoginDialog
import com.instapulse.ui.queue.InstaActionExecutorHud
import com.instapulse.ui.splash.CinematicSplashScreen
import com.instapulse.ui.theme.InstaPulseTheme

class MainActivity : ComponentActivity() {
    private val viewModel: InstaPulseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InstaPulseTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.onTrimMemory(level)
    }
}

@Composable
fun MainApp(viewModel: InstaPulseViewModel) {
    val showSplash by viewModel.showSplash.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val executorState by viewModel.executorState.collectAsState()
    val showLoginModal by viewModel.showLoginModal.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        if (showSplash) {
            CinematicSplashScreen(
                onFinish = { viewModel.finishSplash() }
            )
        } else {
            InstaPulseDashboardScreen(
                state = uiState,
                onTabSelected = viewModel::setActiveTab,
                onSearchChanged = viewModel::setSearchQuery,
                onSubFilterSelected = viewModel::setSubFilter,
                onCycleSort = viewModel::cycleSortOrder,
                onToggleCheck = viewModel::toggleCheck,
                onSelectAllVisible = viewModel::selectAllVisible,
                onClearChecked = viewModel::clearChecked,
                onToggleWhitelist = viewModel::toggleWhitelist,
                onExecuteSingle = viewModel::startSingleAction,
                onStartBatchQueue = viewModel::startBatchQueueFromSelection,
                onStartLiveSync = viewModel::startLiveSync,
                onOpenLoginModal = viewModel::openLoginModal,
                onLogout = viewModel::logout,
                onLoadSampleData = { /* Removed */ },
                onSyncProgress = viewModel::handleSyncProgress,
                onSyncComplete = viewModel::handleSyncComplete,
                onSyncError = viewModel::handleSyncError,
                onActionResult = viewModel::handleActionResult
            )

            // Floating HUD for Action Queue Execution
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                InstaActionExecutorHud(
                    visible = executorState.visible,
                    queue = executorState.queue,
                    currentIndex = executorState.currentIndex,
                    isPaused = executorState.isPaused,
                    countdown = executorState.countdown,
                    statusMessage = executorState.statusMessage,
                    onTogglePause = viewModel::toggleExecutorPause,
                    onCancel = viewModel::cancelExecutor,
                    onActionResult = viewModel::handleActionResult
                )
            }

            // Instagram In-App Authentication Dialog
            InstagramLoginDialog(
                visible = showLoginModal,
                onDismiss = viewModel::closeLoginModal,
                onLoginSuccess = { cookieHeader, dsUserId, csrfToken ->
                    viewModel.onLoginSuccess(cookieHeader, dsUserId, csrfToken)
                }
            )
        }
    }
}
