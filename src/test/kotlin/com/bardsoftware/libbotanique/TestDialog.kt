package com.bardsoftware.libbotanique

import org.junit.jupiter.api.Assertions.assertEquals
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
        val session = userSessionProviderMem(123)
        val json = session.load(sm.landingStateId)!!.asJson()!!

        assertEquals("Hello World", json.get("f1").asText())
    }
}
