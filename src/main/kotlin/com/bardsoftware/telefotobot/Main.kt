package com.bardsoftware.telefotobot

import com.bardsoftware.libbotanique.*
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook
import java.nio.file.Files
import kotlin.io.path.writeBytes

fun main(args: Array<String>) {
    val sm = stateMachine(::userSessionProviderMem) {
        landing {
            command("start")
            menu {
                text = "Привет, ${it.user.displayName}!"
                buttons(
                    "Создать озвучку >>" to "VOICEOVER_CREATE",
                    "Залить видео >>"    to "VIDEO_UPLOAD",
                )
            }
        }
        state("VOICEOVER_CREATE", "s1") {
            dialog {
                text("f1", TextMessage("Пришли текст для озвучки"))
                apply(TextMessage("Озвучиваем?"), TextMessage("Готово!")) { json ->
                    println("Озвучиваем текст: ${json.get("f1").asText()}")
                }
            }
        }
        state("VIDEO_UPLOAD", "s2") {
            dialog {
                video("v1", TextMessage("Жду видео файл")) { bytes ->
                    println("Получено видео: ${bytes.size} байт")
                    val file = Files.createTempFile("video", ".mp4").also { it.writeBytes(bytes) }
                    YouTubeUploader.uploadVideo(file.toAbsolutePath(), "Test video", "Test video description")
                }
                apply(TextMessage("Добавляем?"), TextMessage("Фото добавлено")) { json ->
                    println("Добавляем!!!!")
                }
            }
        }
    }
    val messageProcessorFactory = createMessageProcessorFactory(sm)
    if (args.isNotEmpty() && args[0] == "poll") {
        TelegramBotsApi(DefaultBotSession::class.java).apply {
            registerBot(LongPollingConnector(messageProcessorFactory, testReplyChatId = null, testBecome = null))
        }
    } else {
        TelegramBotsApi(DefaultBotSession::class.java, DefaultWebhook().also {
            it.setInternalUrl("http://0.0.0.0:8080")
        }).apply {
            //registerBot(WebHookConnector(::processMessage).also(::addMenuCommands), SetWebhook(System.getenv("TG_BOT_URL")))
        }
    }

}
