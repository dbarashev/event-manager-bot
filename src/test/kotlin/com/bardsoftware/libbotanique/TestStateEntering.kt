package com.bardsoftware.libbotanique

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue


class TestStateEntering {
    private val VOID_OUTPUT = OutputUi(showButtons = {_, _ ->})
    private val TEST_USER = TgUser("test", "123", "test")
    @Test
    fun `Empty StateMachine Matches Nothing`() {
        val sm = stateMachine(::userSessionProviderMem) {
        }
        val result = sm.handle(
            InputEnvelope(objectNode {}, objectNode {}, TEST_USER),
            VOID_OUTPUT
        )
        assertTrue(result is Err)
    }

    @Test fun `States Are Registered But Do Not Match`() {
        val sm = stateMachine(::userSessionProviderMem) {
            state("STATE_A", "A") {}
            state("STATE_B", "B") {}
        }
        val result = sm.handle(InputEnvelope(objectNode {
            put("#", "C")
        }, objectNode {}, TEST_USER), VOID_OUTPUT)
        assertTrue(result is Err)
    }

    @Test fun `State Matches But No Action Registered`() {
        val sm = stateMachine(::userSessionProviderMem) {
            state("STATE_A", "A") {}
            state("STATE_B", "B") {}
        }
        val result = sm.handle(InputEnvelope(objectNode {
            put("#", "B")
        }, objectNode {}, TEST_USER), VOID_OUTPUT)
        assertTrue(result is Err)
        assertTrue(result.error.contains("No action is registered"), result.error)
    }

    @Test fun `State Matches And Action Is Registered`() {
        val sm = stateMachine(::userSessionProviderMem) {
            state("STATE_A", "A") {}
            state("STATE_B", "B") {
                menu {}
            }
        }
        val result = sm.handle(InputEnvelope(objectNode {
            put("#", "B")
        }, objectNode {}, TEST_USER), VOID_OUTPUT)
        assertTrue(result is Ok)
    }

    @Test fun `Two Matching States, First Registered Wins`() {
        val sm = stateMachine(::userSessionProviderMem) {
            state("STATE_B0", "B") {
                menu {}
            }
            state("STATE_B1", "B") {
            }
        }
        val result = sm.handle(InputEnvelope(objectNode {
            put("#", "B")
        }, objectNode {}, TEST_USER), VOID_OUTPUT)
        assertTrue(result is Ok)
    }
}