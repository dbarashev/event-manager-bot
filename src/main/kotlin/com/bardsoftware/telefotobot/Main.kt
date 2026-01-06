package com.bardsoftware.telefotobot

import com.bardsoftware.libbotanique.*
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook

fun main(args: Array<String>) {
    val sm = stateMachine(::userSessionProviderMem) {
        landing {
            menu {
                text = "Привет, ${it.user.displayName}!"
                buttons(
                    "Список Альбомов >>" to "ALBUM_LIST",
                    "Добавить Фото >>"    to "ALBUM_ADD",
                )
            }
        }
        state("ALBUM_LIST", "s1") {
            action {
                SimpleAction("ALBUM LIST", landingStateId, it) {}
            }
        }
        state("ALBUM_ADD", "s2") {
            dialog {
                intro(TextMessage("Добавить фото"))
                apply(TextMessage("Добавляем?"), TextMessage("Фото добавлено")) { json ->
                    println("Добавляем!!!!")
                }
            }
        }
    }
    val messageProcessor = TelegramBotsMessageProcessor(sm)
    if (args.isNotEmpty() && args[0] == "poll") {
        TelegramBotsApi(DefaultBotSession::class.java).apply {
            registerBot(LongPollingConnector(messageProcessor::processMessage, testReplyChatId = null, testBecome = null))
        }
    } else {
        TelegramBotsApi(DefaultBotSession::class.java, DefaultWebhook().also {
            it.setInternalUrl("http://0.0.0.0:8080")
        }).apply {
            //registerBot(WebHookConnector(::processMessage).also(::addMenuCommands), SetWebhook(System.getenv("TG_BOT_URL")))
        }
    }

}
