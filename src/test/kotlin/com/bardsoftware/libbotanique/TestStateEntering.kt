package com.bardsoftware.libbotanique

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue


class TestStateEntering {
    @Test
    fun `Empty StateMachine Matches Nothing`() {
        val sm = stateMachine {
        }
        val result = sm.handle(
            InputData(objectNode {}, objectNode {}, TgUser("test", "123", "test")),
            OutputUi(showButtons = {_, _ ->})
        )
        assertTrue(result is Err)
    }

    @Test fun `States Are Registered But Do Not Match`() {
        val sm = stateMachine {
            state("STATE_A") {
                trigger {
                    put("A", true)
                }
            }
            state("STATE_B") {
                trigger {
                    put("B", true)
                }
            }
        }
        val result = sm.handle(InputData(objectNode {
            put("A", false)
        }, objectNode {}, TgUser("test", "123", "test")), OutputUi(showButtons = {_, _ ->}))
        assertTrue(result is Err)
    }

    @Test fun `State Matches But No Action Registered`() {
        val sm = stateMachine {
            state("STATE_A") {
                trigger {
                    put("A", true)
                }
            }
            state("STATE_B") {
                trigger {
                    put("B", true)
                }
            }
        }
        val result = sm.handle(InputData(objectNode {
            put("B", true)
        }, objectNode {}, TgUser("test", "123", "test")), OutputUi(showButtons = {_, _ ->}))
        assertTrue(result is Err)
        assertTrue(result.error.contains("No action is registered"))
    }

    @Test fun `State Matches And Action Is Registered`() {
        val sm = stateMachine {
            state("STATE_A") {
                trigger {
                    put("A", true)
                }
            }
            state("STATE_B") {
                trigger {
                    put("B", true)
                }
                menu {}
            }
        }
        val result = sm.handle(InputData(objectNode {
            put("B", true)
        }, objectNode {}, TgUser("test", "123", "test")), OutputUi(showButtons = {_, _ ->}))
        assertTrue(result is Ok)
    }

    @Test fun `Two Matching States, First Registered Wins`() {
        val sm = stateMachine {
            state("STATE_B0") {
                trigger {
                    put("B", true)
                }
                menu {}
            }
            state("STATE_B1") {
                trigger {
                    put("B", true)
                }
            }
        }
        val result = sm.handle(InputData(objectNode {
            put("B", true)
        }, objectNode {}, TgUser("test", "123", "test")), OutputUi(showButtons = {_, _ ->}))
        assertTrue(result is Ok)
    }

}