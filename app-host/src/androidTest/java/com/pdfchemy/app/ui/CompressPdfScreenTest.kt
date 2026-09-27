package com.pdfchemy.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.ToolCard
import org.junit.Rule
import org.junit.Test

class CompressPdfScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun compressPdfScreen_displaysKeyElements() {
        // Arrange
        composeTestRule.setContent {
            ToolCard(
                title = "Compress PDF",
                subtitle = "Reduce file size",
                icon = ImageVector.Builder("dummy", 24.dp, 24.dp, 24f, 24f).build(),
                onClick = {}
            )
        }

        // Assert
        composeTestRule.onNodeWithText("Compress PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reduce file size").assertIsDisplayed()
    }
}
