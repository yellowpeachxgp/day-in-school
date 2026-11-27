package cn.edu.haut.campus

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证本仓 W0-10 使用的 Robolectric Compose 语义夹具可读文案并触发真实 OnClick。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeSemanticsHarnessSmokeTest {

    @Test
    fun harnessReadsTextAndInvokesClickableSemantics() {
        var clicks = 0
        ComposeSemanticsHarness.launch {
            MaterialTheme {
                Column {
                    Text("语义夹具烟测")
                    Button(onClick = { clicks += 1 }) {
                        Text("点按回归")
                    }
                }
            }
        }.use { harness ->
            harness.requireText("语义夹具烟测")
            assertTrue(harness.clickText("点按回归"))
            assertEquals(1, clicks)
        }
    }
}
