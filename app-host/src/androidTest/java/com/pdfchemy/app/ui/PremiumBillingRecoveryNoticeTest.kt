package com.pdfchemy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.billing.BillingUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PremiumBillingRecoveryNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paidUserSeesOfflineWarningAndCanRetryWithoutPurchaseControls() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("Premium active")
                    PremiumBillingRecoveryNotice(
                        BillingUiState(loading = false, message = "Offline cache needs recovery", canRetry = true, entitlementStorageFailed = true)
                    ) { retries++ }
                }
            }
        }
        compose.onNodeWithText("Premium active").assertIsDisplayed()
        compose.onNodeWithText("Offline cache needs recovery").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun healthyPremiumStateHasNoRecoveryPrompt() {
        compose.setContent { MaterialTheme { PremiumBillingRecoveryNotice(BillingUiState(loading = false)) {} } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun ongoingCacheSaveDoesNotOfferOverlappingRetry() {
        compose.setContent {
            MaterialTheme {
                PremiumBillingRecoveryNotice(
                    BillingUiState(loading = true, message = "Saving offline status", entitlementStorageFailed = true)
                ) {}
            }
        }
        compose.onNodeWithText("Saving offline status").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
