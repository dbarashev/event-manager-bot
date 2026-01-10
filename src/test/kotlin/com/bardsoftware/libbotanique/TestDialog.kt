package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestDialog {
    private val VOID_OUTPUT = OutputUi(showButtons = { _, _ -> })
    private val TEST_USER = TgUser("test", "123", "test")

    @Test
    fun `test dialog stores text input`() {
        val sm = stateMachine(::userSessionProviderMem) {
            landing {
                dialog {
                    text("f1", TextMessage("Prompt"))
                    apply(TextMessage("Confirm?"), TextMessage("Done")) {
                        // success code
                    }
                }
            }
        }

        val landingState = sm.getState(sm.landingStateId)!!

        // 1. Initial hit to start the dialog
        sm.handle(InputEnvelope(stateJson = landingState.stateJson, contextJson = emptyNode(), user = TEST_USER, contents = InputTransition(landingState.stateJson)), VOID_OUTPUT)

        // 2. Send text input
        sm.handle(InputEnvelope(stateJson = emptyNode(), contextJson = emptyNode(), user = TEST_USER, contents = InputText("Hello World")), VOID_OUTPUT)

        // Check session data
        val session =  sm.sessionProvider(TEST_USER.id.toLong())
        val json = session.getDialogData(sm.landingStateId)

        assertEquals("Hello World", json.get("f1").asText())
    }

    @Test
    fun `cancel step returns to the specified state`() {
        val sm = stateMachine(::userSessionProviderMem) {
            landing {
                dialog {
                    text("f1", TextMessage("Prompt"))
                    apply(TextMessage("Confirm?"), TextMessage("Done")) {
                        // success code
                    }
                    cancel("EXIT")
                }
            }
            state("EXIT", "s1") {
                action {
                    SimpleAction("EXIT", landingStateId, it) { }
                }
            }
        }

        val landingState = sm.getState(sm.landingStateId)!!
        // 1. Initial hit to start the dialog
        sm.handle(InputEnvelope(stateJson = landingState.stateJson, contextJson = emptyNode(), user = TEST_USER, contents = InputTransition(landingState.stateJson)), VOID_OUTPUT)
        // 2. Send text input, expect confirmation buttons in the output.
        val step2input = InputEnvelope(stateJson = emptyNode(), contextJson = emptyNode(), user = TEST_USER, contents = InputText("Hello World"))
        var step2output: ObjectNode? = null
        sm.handle(step2input,
            OutputUi({ text: TextMessage, buttons: ButtonBlock ->
                assertEquals(2, buttons.buttons.size)
                step2output = buttons.buttons[1].callbackJson(step2input, sm)
            })
        )
        // 3. Send "Cancel" button click, expect to be in the same state with "dialog cancelled" message and "Go back" button
        val step3input = createCallbackInput(step2output!!, user = TEST_USER)
        var step3output: ObjectNode? = null
        sm.handle(step3input, OutputUi({ text, btnBlock ->
            assertEquals(1, btnBlock.buttons.size)
            step3output = btnBlock.buttons[0].callbackJson(step3input, sm)
        }))
        // 4. Send "Go back" button click, expect to be in EXIT state
        val step4input = createCallbackInput(step3output!!, TEST_USER)
        sm.handle(step4input, OutputUi({ text, _ -> assertEquals("EXIT", text.text)}))
        val dialogData = sm.sessionProvider(TEST_USER.id.toLong()).load(sm.landingStateId)?.asJson() ?: emptyNode()
        assertTrue(dialogData.isEmpty, dialogData.toString())
    }
}

private class TestOutputUi {
    fun showButtons(text: TextMessage, buttonBlock: ButtonBlock) {}

}