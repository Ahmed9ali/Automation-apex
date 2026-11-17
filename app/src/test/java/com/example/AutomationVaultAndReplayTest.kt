package com.example

import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.ActiveRecordingSession
import com.example.automation.AutomationActionType
import com.example.automation.AutomationEngine
import com.example.automation.AutomationHudMode
import com.example.automation.AutomationStep
import com.example.automation.AutomationStepCodec
import com.example.automation.AutomationType
import com.example.automation.AutomationVaultCategory
import com.example.automation.AutomationVaultManager
import com.example.automation.PageInspectionState
import com.example.automation.Phase6ExecutionCoordinator
import com.example.automation.RecordedDomEvent
import com.example.automation.RecordingStatus
import com.example.automation.RecordingVaultCandidate
import com.example.automation.TabNavigationSynchronizer
import com.example.automation.VaultFileImportCandidate
import com.example.automation.WorkflowDocument
import com.example.automation.WorkflowExecutionState
import com.example.data.local.ApexDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.ui.browser.AutomationHudSheet
import com.example.ui.browser.AutomationVaultScreenOverlay
import com.example.ui.theme.ApexBrowserTheme
import com.example.ui.viewmodel.BrowserViewModel
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomationVaultAndReplayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeWebView(
        context: Context,
        var fakeUrl: String = "https://shop.example.com/signup",
        private val evaluator: (script: String, callback: ValueCallback<String>?) -> Unit
    ) : WebView(context) {
        val evaluatedScripts = mutableListOf<String>()

        override fun getUrl(): String = fakeUrl

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            evaluatedScripts.add(script)
            evaluator(script, resultCallback)
        }
    }

    private class Harness(
        val db: ApexDatabase,
        val repo: BrowserRepository,
        val vm: BrowserViewModel,
        val context: Context,
        private val store: ViewModelStore
    ) {
        fun close() {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    private fun createHarness(
        engine: AutomationEngine = AutomationEngine(
            jsEvalTimeoutMs = 1_500L,
            navTimeoutMs = 1_500L,
            domReadyTimeoutMs = 450L,
            domStabilityPollMs = 20L
        )
    ): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val prefs = UserPreferencesRepository(context)
        val sharedPrefs = context.getSharedPreferences(
            "automation_vault_replay_${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        val repo = BrowserRepository(db.apexDao(), prefs, sharedPrefs)
        val dl = FastDownloadEngine(context, repo)
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BrowserViewModel(
                        repository = repo,
                        fastDownloadEngine = dl,
                        automationEngine = engine
                    ) as T
                }
            }
        )[BrowserViewModel::class.java]
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return Harness(db = db, repo = repo, vm = vm, context = context, store = store)
    }

    private fun idleMainUntil(timeoutMs: Long = 4_000L, predicate: () -> Boolean) {
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        mainLooper.idle()
        while (!predicate() && System.currentTimeMillis() < deadline) {
            mainLooper.idleFor(20L, TimeUnit.MILLISECONDS)
        }
        mainLooper.idle()
        assertTrue("Timed out waiting for condition", predicate())
    }

    @Test
    fun firstRecording_grabsSelectedValuesIntoAutomationVault_whileKeepingStepPasswordEmpty() {
        val h = createHarness()
        try {
            val tabId = h.vm.uiState.value.activeTabId
            val targetUrl = "https://accounts.example.com/register"
            h.vm.onWebViewPageFinished(tabId, "Sign Up", targetUrl, canGoBack = false, canGoForward = false)

            val fakeWv = FakeWebView(h.context, fakeUrl = targetUrl) { script, cb ->
                val sidMatch = Regex("""sessionId:\s*("(?:[^"\\]|\\.)*")""").find(script)
                val sid = sidMatch?.groupValues?.getOrNull(1)?.let { JSONObject("{\"v\":$it}").optString("v") } ?: ""
                val payload = if (script.contains("__apexRecorderInstance = inst")) {
                    JSONObject().put("ok", true).put("sessionId", sid).toString()
                } else {
                    JSONObject().put("ok", true).toString()
                }
                cb?.onReceiveValue(JSONObject.quote(payload))
            }
            h.vm.registerWebView(tabId, fakeWv)

            h.vm.startRecording()
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.RECORDING }
            val session = h.vm.uiState.value.recordingSession

            // 1) Email input
            h.vm.onRecordedDomEvent(
                RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = tabId,
                    origin = "https://accounts.example.com",
                    sequenceNumber = 1L,
                    eventType = "INPUT",
                    targetLabel = "Email Address",
                    selector = "input[type=\"email\"][name=\"email\"]",
                    value = "alex@example.com",
                    isDestructive = false
                ),
                tabId,
                fakeWv,
                targetUrl
            )
            // 2) Phone input (user will later uncheck this one)
            h.vm.onRecordedDomEvent(
                RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = tabId,
                    origin = "https://accounts.example.com",
                    sequenceNumber = 2L,
                    eventType = "INPUT",
                    targetLabel = "Phone Number",
                    selector = "input[type=\"tel\"][name=\"phone\"]",
                    value = "+15551234567",
                    isDestructive = false
                ),
                tabId,
                fakeWv,
                targetUrl
            )
            // 3) Password input: step.value must stay empty while sensitiveCapturedValue feeds Automation Vault
            h.vm.onRecordedDomEvent(
                RecordedDomEvent(
                    sessionId = session.sessionId,
                    tabId = tabId,
                    origin = "https://accounts.example.com",
                    sequenceNumber = 3L,
                    eventType = "INPUT",
                    targetLabel = "Password",
                    selector = "input[type=\"password\"]",
                    value = "",
                    isDestructive = false,
                    isPasswordField = true,
                    fieldRoleHint = "PASSWORD",
                    sensitiveCapturedValue = "SuperSecret!99"
                ),
                tabId,
                fakeWv,
                targetUrl
            )
            idleMainUntil { h.vm.uiState.value.recordingSession.steps.size == 3 }

            val passwordStepBeforeStop = h.vm.uiState.value.recordingSession.steps[2]
            assertEquals("", passwordStepBeforeStop.value)

            // Stop recording -> grabbed candidates appear for user selection
            h.vm.stopRecording()
            idleMainUntil { h.vm.uiState.value.recordingVaultCandidates.size == 3 }

            val candidates = h.vm.uiState.value.recordingVaultCandidates
            assertEquals(AutomationVaultCategory.EMAIL, candidates[0].category)
            assertEquals("alex@example.com", candidates[0].capturedValue)
            assertTrue(candidates[0].isSelected)

            assertEquals(AutomationVaultCategory.PHONE, candidates[1].category)
            assertEquals("+15551234567", candidates[1].capturedValue)
            assertTrue(candidates[1].isSelected)

            assertEquals(AutomationVaultCategory.PASSWORD, candidates[2].category)
            assertEquals("SuperSecret!99", candidates[2].capturedValue)
            assertTrue(candidates[2].hasSecretCapture)
            assertTrue(candidates[2].isSelected)

            // User deselects Phone (kept literal) and updates Email label
            h.vm.toggleRecordingVaultCandidate(candidates[1].stepId)
            h.vm.updateRecordingVaultCandidate(
                stepId = candidates[0].stepId,
                category = AutomationVaultCategory.EMAIL,
                label = "Primary Account Email",
                value = "alex@example.com"
            )

            h.vm.saveRecordingWorkflow("Account Signup")
            idleMainUntil { h.vm.uiState.value.recordingSession.status == RecordingStatus.IDLE }

            runBlocking {
                val savedWorkflow = h.db.apexDao().observeAutomationWorkflows().first().first()
                assertFalse("Password must never be stored plaintext in workflow JSON", savedWorkflow.stepsJson.contains("SuperSecret!99"))
                val savedSteps = AutomationStepCodec.decode(savedWorkflow.stepsJson)
                assertEquals(3, savedSteps.size)

                // Selected Email & Password steps are bound to Automation Vault
                assertNotNull(savedSteps[0].vaultEntryId)
                assertEquals("EMAIL", savedSteps[0].vaultCategory)
                assertEquals("", savedSteps[0].value)

                // Unselected Phone step stays literal
                assertEquals(null, savedSteps[1].vaultEntryId)
                assertEquals("+15551234567", savedSteps[1].value)

                // Password step is bound to Automation Vault (no Credential Vault block needed)
                assertNotNull(savedSteps[2].vaultEntryId)
                assertEquals("PASSWORD", savedSteps[2].vaultCategory)
                assertEquals("", savedSteps[2].value)
                assertFalse(savedSteps[2].requiresCredential)

                val vaultEntries = h.db.apexDao().getAllAutomationVaultEntriesOnce()
                assertEquals(2, vaultEntries.size)
                assertEquals("alex@example.com", h.repo.automationVaultManager.decryptEntryValue(savedSteps[0].vaultEntryId!!).getOrThrow())
                assertEquals("SuperSecret!99", h.repo.automationVaultManager.decryptEntryValue(savedSteps[2].vaultEntryId!!).getOrThrow())
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun unlimitedReplay_readsUpdatedValuesFromAutomationVault_onEveryReplay() {
        val h = createHarness()
        try {
            val targetUrl = "https://portal.example.com/login"
            val tabId = h.vm.uiState.value.activeTabId
            h.vm.onWebViewPageFinished(tabId, "Portal", targetUrl, canGoBack = false, canGoForward = false)

            val injectedScripts = mutableListOf<String>()
            val fakeWv = FakeWebView(h.context, fakeUrl = targetUrl) { script, cb ->
                injectedScripts.add(script)
                val reply = if (script.contains("readyState")) {
                    JSONObject()
                        .put("readyState", "complete")
                        .put("url", targetUrl)
                        .put("shape", "2:24")
                        .put("targetReady", true)
                        .toString()
                } else {
                    JSONObject()
                        .put("ok", true)
                        .put("msg", "Filled input")
                        .put("resolvedSelector", "input[name=\"email\"]")
                        .put("verified", true)
                        .toString()
                }
                cb?.onReceiveValue(JSONObject.quote(reply))
            }
            h.vm.registerWebView(tabId, fakeWv)

            val (emailEntryId, passEntryId, workflowId) = runBlocking {
                val emailEntry = h.repo.automationVaultManager.saveOrUpdateEntry(
                    category = AutomationVaultCategory.EMAIL,
                    label = "Work Email",
                    plainValue = "user1@example.com",
                    websiteOrigin = "https://portal.example.com",
                    isSensitive = false
                ).getOrThrow()
                val passEntry = h.repo.automationVaultManager.saveOrUpdateEntry(
                    category = AutomationVaultCategory.PASSWORD,
                    label = "Work Password",
                    plainValue = "FirstPass111",
                    websiteOrigin = "https://portal.example.com",
                    isSensitive = true
                ).getOrThrow()

                val wfId = h.repo.saveAutomationWorkflow(
                    name = "Portal Sign In",
                    domain = "portal.example.com",
                    sourceUrl = targetUrl,
                    type = AutomationType.RECORDED,
                    steps = listOf(
                        AutomationStep(
                            id = "s1",
                            order = 1,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Email",
                            selector = "input[name=\"email\"]",
                            value = "",
                            description = "Enter Email",
                            vaultEntryId = emailEntry.entryId,
                            vaultCategory = AutomationVaultCategory.EMAIL.id
                        ),
                        AutomationStep(
                            id = "s2",
                            order = 2,
                            actionType = AutomationActionType.INPUT,
                            targetLabel = "Password",
                            selector = "input[type=\"password\"]",
                            value = "",
                            description = "Enter Password",
                            isDestructive = false,
                            vaultEntryId = passEntry.entryId,
                            vaultCategory = AutomationVaultCategory.PASSWORD.id
                        )
                    )
                )
                Triple(emailEntry.entryId, passEntry.entryId, wfId)
            }

            // Replay #1
            h.vm.replayWorkflow(workflowId)
            idleMainUntil { h.vm.uiState.value.executionState?.status == "COMPLETED" }
            assertTrue(injectedScripts.any { it.contains("user1@example.com") })
            assertTrue(injectedScripts.any { it.contains("FirstPass111") })

            // Update values in Automation Vault WITHOUT re-recording workflow
            injectedScripts.clear()
            runBlocking {
                h.repo.automationVaultManager.saveOrUpdateEntry(
                    entryId = emailEntryId,
                    category = AutomationVaultCategory.EMAIL,
                    label = "Work Email",
                    plainValue = "rotated@example.com",
                    websiteOrigin = "https://portal.example.com",
                    isSensitive = false
                ).getOrThrow()
                h.repo.automationVaultManager.saveOrUpdateEntry(
                    entryId = passEntryId,
                    category = AutomationVaultCategory.PASSWORD,
                    label = "Work Password",
                    plainValue = "SecondPass222!",
                    websiteOrigin = "https://portal.example.com",
                    isSensitive = true
                ).getOrThrow()
            }

            // Replay #2 picks up updated Automation Vault values automatically
            h.vm.replayWorkflow(workflowId)
            idleMainUntil { h.vm.uiState.value.executionState?.status == "COMPLETED" }
            assertTrue(injectedScripts.any { it.contains("rotated@example.com") })
            assertTrue(injectedScripts.any { it.contains("SecondPass222!") })
            assertFalse(injectedScripts.any { it.contains("user1@example.com") })
            assertFalse(injectedScripts.any { it.contains("FirstPass111") })

            // Replay #3 works cleanly right after Replay #2 (unlimited replays)
            injectedScripts.clear()
            h.vm.replayWorkflow(workflowId)
            idleMainUntil { h.vm.uiState.value.executionState?.status == "COMPLETED" }
            assertTrue(injectedScripts.any { it.contains("rotated@example.com") })
            runBlocking {
                val refreshed = h.repo.automationVaultManager.getEntryById(emailEntryId)!!
                assertNotNull("Expected lastUsedAt to be recorded on replay", refreshed.lastUsedAt)
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun workflowStart_waitsForWebsiteLoadCompletionBeforeRunningStep1() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine(
            jsEvalTimeoutMs = 1_500L,
            navTimeoutMs = 2_000L,
            domReadyTimeoutMs = 1_500L,
            domStabilityPollMs = 20L
        )
        val coordinator = Phase6ExecutionCoordinator(engine)
        val navSync = TabNavigationSynchronizer()
        val tabId = "tab_wait_load"
        val url = "https://slow.example.com/checkout"

        // Tab URL matches workflow URL, but website is still actively LOADING
        navSync.onPageStarted(tabId, url)

        var stepExecutedBeforeLoadFinished = false
        val wv = FakeWebView(context, fakeUrl = url) { script, cb ->
            if (script.contains("readyState")) {
                val ready = navSync.snapshot(tabId).phase.name == "FINISHED"
                val reply = JSONObject()
                    .put("readyState", if (ready) "complete" else "loading")
                    .put("url", url)
                    .put("shape", if (ready) "4:120" else "0:0")
                    .put("targetReady", ready)
                    .toString()
                cb?.onReceiveValue(JSONObject.quote(reply))
            } else {
                if (navSync.snapshot(tabId).phase.name != "FINISHED") {
                    stepExecutedBeforeLoadFinished = true
                }
                val reply = JSONObject()
                    .put("ok", true)
                    .put("msg", "Clicked Continue")
                    .put("resolvedSelector", "#continue-btn")
                    .put("verified", true)
                    .toString()
                cb?.onReceiveValue(JSONObject.quote(reply))
            }
        }

        val steps = listOf(
            AutomationStep(
                id = "step_1",
                order = 1,
                actionType = AutomationActionType.CLICK,
                targetLabel = "Continue",
                selector = "#continue-btn",
                value = "",
                description = "Click Continue"
            )
        )
        val workflow = com.example.data.local.AutomationWorkflowEntity(
            id = 42L,
            name = "Checkout Flow",
            domain = "slow.example.com",
            sourceUrl = url,
            type = AutomationType.RECORDED.id,
            instructionsText = "",
            stepsJson = AutomationStepCodec.encode(steps),
            stepCount = 1,
            createdAt = 1L,
            updatedAt = 1L
        )

        val progressLogs = mutableListOf<String>()
        val deferred = async {
            coordinator.executeDocumentWorkflow(
                workflow = workflow,
                document = WorkflowDocument(steps = steps),
                currentTabUrl = url,
                webViewProvider = { wv },
                onNavigateUrl = {},
                onStepProgress = { _, msg -> progressLogs.add(msg) },
                tabId = tabId,
                navigationSynchronizer = navSync
            )
        }

        delay(120L)
        assertFalse("Step 1 must wait while website is still loading", stepExecutedBeforeLoadFinished)
        navSync.onPageFinished(tabId, url)

        val outcome = deferred.await()
        assertEquals("COMPLETED", outcome.status)
        assertFalse("Step 1 must never execute before page load finished", stepExecutedBeforeLoadFinished)
        assertTrue(progressLogs.any { it.contains("Waiting for website", ignoreCase = true) })
    }

    @Test
    fun automationHudAndVaultOverlay_exposeVaultQuickActionAndRecordingCandidates() {
        var hudMode by mutableStateOf(AutomationHudMode.RECORDING)
        var openedVault by mutableStateOf(false)
        var toggledStepId: String? = null
        var candidates by mutableStateOf(
            listOf(
                RecordingVaultCandidate(
                    stepId = "rec_1",
                    stepOrder = 1,
                    targetLabel = "Phone",
                    selector = "input[type=\"tel\"]",
                    category = AutomationVaultCategory.PHONE,
                    vaultLabel = "Phone (shop.example.com)",
                    capturedValue = "555-0199",
                    isSensitive = false,
                    isSelected = true
                )
            )
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                if (openedVault) {
                    AutomationVaultScreenOverlay(
                        entries = emptyList(),
                        revealedValues = emptyMap(),
                        activeTabUrl = "https://shop.example.com",
                        onSaveEntry = { _, _, _, _, _, _ -> },
                        onDeleteEntry = {},
                        onRevealEntry = {},
                        onHideEntry = {},
                        onDismiss = { openedVault = false }
                    )
                } else {
                    AutomationHudSheet(
                        hudMode = hudMode,
                        pageInspection = PageInspectionState(domain = "shop.example.com", url = "https://shop.example.com"),
                        isHomePage = false,
                        recordingSession = ActiveRecordingSession(
                            status = RecordingStatus.STOPPED,
                            targetDomain = "shop.example.com",
                            targetUrl = "https://shop.example.com",
                            steps = listOf(
                                AutomationStep(
                                    id = "rec_1",
                                    order = 1,
                                    actionType = AutomationActionType.INPUT,
                                    targetLabel = "Phone",
                                    selector = "input[type=\"tel\"]",
                                    value = "555-0199",
                                    description = "Enter Phone"
                                )
                            )
                        ),
                        instructionDraftText = "",
                        instructionDraftSteps = emptyList(),
                        executionState = WorkflowExecutionState(
                            workflowId = 7L,
                            workflowName = "Checkout",
                            status = "COMPLETED",
                            currentStepIndex = 1,
                            totalSteps = 1
                        ),
                        workflows = emptyList(),
                        executions = emptyList(),
                        recordingVaultCandidates = candidates,
                        automationVaultCount = 2,
                        onSelectMode = { hudMode = it },
                        onScanPage = {},
                        onStartRecording = {},
                        onPauseRecording = {},
                        onResumeRecording = {},
                        onStopRecording = {},
                        onMinimizeRecording = {},
                        onRenameRecordingDraft = {},
                        onEditRecordingStep = { _, _, _ -> },
                        onDeleteRecordingStep = {},
                        onSaveRecording = {},
                        onDiscardRecording = {},
                        onInstructionTextChange = {},
                        onAddInstructions = {},
                        onClearInstructions = {},
                        onReviewInstructionSteps = {},
                        onEditInstructionStep = { _, _, _, _ -> },
                        onDeleteInstructionStep = {},
                        onMoveInstructionStepUp = {},
                        onMoveInstructionStepDown = {},
                        onSaveInstructionsAsWorkflow = {},
                        onExecuteInstructions = {},
                        onRunWorkflow = {},
                        onOpenWorkflowDetails = {},
                        onOpenWorkspace = {},
                        onOpenNanoRetrieverPanel = {},
                        onConfirmDestructiveExecution = {},
                        onCancelDestructiveExecution = {},
                        onClearExecutionBanner = {},
                        onOpenAutomationVault = { openedVault = true },
                        onToggleRecordingVaultCandidate = { id ->
                            toggledStepId = id
                            candidates = candidates.map {
                                if (it.stepId == id) it.copy(isSelected = !it.isSelected) else it
                            }
                        },
                        onDismiss = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("recording_vault_candidate_1").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("recording_vault_check_1").performScrollTo().performClick()
        assertEquals("rec_1", toggledStepId)

        hudMode = AutomationHudMode.OVERVIEW
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hud_quick_vault").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("automation_vault_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("automation_vault_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hud_quick_vault").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun vaultFileUpload_parsesCsvJsonAndKeyValueFiles_andImportsOnlyUserSelectedEntries() = runBlocking {
        val h = createHarness()
        try {
            val csvText = """
                name,url,username,password
                GitHub,https://github.com/login,dev@example.com,GitSecret!99
                Shop,https://shop.example.com,shopper1,ShopPass!22
            """.trimIndent()
            val csvCandidates = AutomationVaultManager.parseImportContent(csvText)
            assertEquals(4, csvCandidates.size)
            assertTrue(csvCandidates.any { it.category == AutomationVaultCategory.EMAIL && it.value == "dev@example.com" })
            assertTrue(csvCandidates.any { it.category == AutomationVaultCategory.PASSWORD && it.value == "GitSecret!99" && it.isSensitive })
            assertTrue(csvCandidates.any { it.category == AutomationVaultCategory.USERNAME && it.value == "shopper1" })

            val kvText = """
                # Personal profile values
                phone: +1 555-010-2020
                address = 742 Evergreen Terrace
                portal_password: Portal!Secret7
            """.trimIndent()
            val kvCandidates = AutomationVaultManager.parseImportContent(kvText)
            assertEquals(3, kvCandidates.size)
            assertTrue(kvCandidates.any { it.category == AutomationVaultCategory.PHONE && it.value == "+1 555-010-2020" })
            assertTrue(kvCandidates.any { it.category == AutomationVaultCategory.ADDRESS && it.value == "742 Evergreen Terrace" })
            assertTrue(kvCandidates.any { it.category == AutomationVaultCategory.PASSWORD && it.value == "Portal!Secret7" })

            // User deselects the address line and imports only phone + password
            val userSelection = kvCandidates.map { item ->
                if (item.category == AutomationVaultCategory.ADDRESS) item.copy(isSelected = false) else item
            }
            h.vm.importAutomationVaultCandidates(userSelection)
            idleMainUntil { runBlocking { h.repo.automationVaultManager.getAllEntriesOnce().size == 2 } }

            val stored = h.repo.automationVaultManager.getAllEntriesOnce()
            assertEquals(2, stored.size)
            assertFalse(stored.any { it.category == AutomationVaultCategory.ADDRESS.id })
            val passEntry = stored.first { it.category == AutomationVaultCategory.PASSWORD.id }
            assertEquals("Portal!Secret7", h.repo.automationVaultManager.decryptEntryValue(passEntry.entryId).getOrThrow())
        } finally {
            h.close()
        }
    }

    @Test
    fun automationVaultScreen_singleLineSearchBar_andUploadFileDialogSelectionFlow() {
        var importedSelection by mutableStateOf<List<VaultFileImportCandidate>>(emptyList())

        composeTestRule.setContent {
            ApexBrowserTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.width(360.dp)
                ) {
                    AutomationVaultScreenOverlay(
                        entries = emptyList(),
                        revealedValues = emptyMap(),
                        activeTabUrl = "https://portal.example.com",
                        onSaveEntry = { _, _, _, _, _, _ -> },
                        onImportFileCandidates = { candidates ->
                            importedSelection = candidates.filter { it.isSelected }
                        },
                        onDeleteEntry = {},
                        onRevealEntry = {},
                        onHideEntry = {},
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Search bar must stay single-line height even at compact 360.dp phone width
        val searchBounds = composeTestRule.onNodeWithTag("automation_vault_search_input")
            .assertIsDisplayed()
            .getBoundsInRoot()
        val searchHeight = searchBounds.bottom - searchBounds.top
        assertTrue(
            "Expected single-line search bar height <= 68.dp, was $searchHeight",
            searchHeight <= 68.dp
        )

        // Open Upload File dialog and paste multi-entry content
        composeTestRule.onNodeWithTag("automation_vault_upload_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("vault_upload_choose_file_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_upload_raw_input").performTextInput(
            "email: alice@example.com\nphone: +15550199\npassword: MySecret!99"
        )
        composeTestRule.waitForIdle()

        // All 3 parsed entries appear; deselect the 2nd item (phone) before confirming
        composeTestRule.onNodeWithTag("vault_upload_item_0").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_upload_item_1").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_upload_item_2").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_upload_item_checkbox_1").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("vault_upload_selection_summary").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 of 3 selected", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_upload_confirm_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(2, importedSelection.size)
        assertTrue(importedSelection.any { it.category == AutomationVaultCategory.EMAIL && it.value == "alice@example.com" })
        assertTrue(importedSelection.any { it.category == AutomationVaultCategory.PASSWORD && it.value == "MySecret!99" })
        assertFalse(importedSelection.any { it.category == AutomationVaultCategory.PHONE })
    }
}
