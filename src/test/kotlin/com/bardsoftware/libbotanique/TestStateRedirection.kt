package com.bardsoftware.libbotanique

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestStateRedirection {
    private val VOID_OUTPUT = OutputUi(showButtons = {_, _ ->})
    private val TEST_USER = TgUser("test", "123", "test")
    @Test fun `Redirect to State B when entering landing`() {
        var handlerCallsStateA = 0
        var handlerCallsStateB = 0
        val sm = stateMachine(::userSessionProviderMem) {
            landing {
                action {
                    SimpleAction("", landingStateId, it) {
                        handlerCallsStateA++
                    }
                }
            }
            state("STATE_B", "B") {
                dialog {  }
                action {
                    SimpleAction("", landingStateId, it) {
                        handlerCallsStateB++
                    }
                }
            }
        }
        // Emulate transition to state B
        val transition = sm.getState("STATE_B")!!.stateJson
        sm.handle(InputEnvelope(stateJson = transition, contextJson = emptyNode(), user = TEST_USER, contents = InputTransition(transition)), VOID_OUTPUT)
        assertEquals(1, handlerCallsStateB)

        // Emulate receiving a text message. It has no callback data, so it should be handled by the landing state, but
        // since we have a redirection, it is handled by state B.
        sm.handle(InputEnvelope(stateJson = emptyNode(), contextJson = emptyNode(), user = TEST_USER, contents = InputText("test")), VOID_OUTPUT)
        assertEquals(2, handlerCallsStateB)
        assertEquals(0, handlerCallsStateA)
    }

    @Test fun `Redirect clears when returning to landing`() {
        var handlerCallsStateA = 0
        var handlerCallsStateB = 0
        val sm = stateMachine(::userSessionProviderMem) {
            landing {
                action {
                    SimpleAction("", landingStateId, it) {
                        handlerCallsStateA++
                    }
                }
            }
            state("STATE_B", "B") {
                action {
                    SimpleAction("", landingStateId, it) {
                        handlerCallsStateB++
                    }
                }
            }
        }
        // Emulate transition to state B
        val transitionB = sm.getState("STATE_B")!!.stateJson
        sm.handle(InputEnvelope(stateJson = transitionB, contextJson = emptyNode(), user = TEST_USER, contents = InputTransition(transitionB)), VOID_OUTPUT)
        assertEquals(1, handlerCallsStateB)
        assertEquals(0, handlerCallsStateA)

        val transitionLanding = sm.getState(sm.landingStateId)!!.stateJson
        sm.handle(InputEnvelope(stateJson = transitionLanding, contextJson = emptyNode(), user = TEST_USER, contents = InputTransition(transitionLanding)), VOID_OUTPUT)
        assertEquals(1, handlerCallsStateB)
        assertEquals(1, handlerCallsStateA)
    }

}