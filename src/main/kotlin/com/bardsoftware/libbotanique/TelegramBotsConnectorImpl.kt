package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.*
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.Serializable

typealias MessageProcessorFactory = (MessageSender) -> MessageProcessor

fun createMessageProcessorFactory(stateMachine: BotStateMachine): MessageProcessorFactory {
    return { messageSender -> TelegramBotsMessageProcessor(stateMachine, messageSender)::processMessage }
}
class TelegramBotsMessageProcessor(private val stateMachine: BotStateMachine, private val messageSender: MessageSender) {
    fun processMessage(update: Update) {
        createVideoInputEnvelope(update)
            .orElse { createPhotoInputEnvelope(update) }
            .orElse { createCommandInputEnvelope(update) }
            .orElse { createTextInputEnvelope(update) }
            .orElse { createCallbackInputEnvelope(update) }
            .orElse { Ok(InputEnvelope(objectNode {}, objectNode {}, update.getTgUser(), contents = InputVoid())) }
            .onSuccess {
                stateMachine.handle(it, createOutputUi(update, it, messageSender))
            }
    }

    private fun createOutputUi(update: Update, inputEnvelope: InputEnvelope, messageSender: MessageSender): OutputUi {
        return OutputUi(
            showButtons = {text, buttonBlock ->
                val inplaceUpdate = buttonBlock.inplaceUpdate && update.callbackQuery?.message?.messageId != null
                val isMarkdown = text.markup == TextMarkup.MARKDOWN
                val replyText = text.text.ifBlank { "ПУСТОЙ ОТВЕТ" }

                val replyKeyboard =
                    if (buttonBlock.buttons.isEmpty()) null
                else
                    InlineKeyboardMarkup(
                            buttonBlock.buttons.map { botButton ->
                                InlineKeyboardButton(botButton.label).also { btn ->
                                    if (botButton.payload != null) {
                                        btn.callbackData = botButton.payload
                                        return@also
                                    }
                                    stateMachine.getState(botButton.targetState)?.let {state ->
                                        btn.callbackData = json(state.stateJson) {
                                            setContext(botButton.output(inputEnvelope).contextJson)
                                        }
                                    }
                                }
                            }.chunked(buttonBlock.columnCount)
                        )

                val reply =
                    if (inplaceUpdate) {
                        EditMessageText().apply {
                            messageId = update.getMessageId()
                            chatId = update.getReplyChatId().toString()
                            if (isMarkdown) parseMode = ParseMode.MARKDOWNV2
                            this.text = replyText
                            if (replyKeyboard != null) replyMarkup = replyKeyboard
                        }
                    } else {
                        SendMessage().apply {
                            chatId = update.getReplyChatId().toString()
                            enableMarkdownV2(isMarkdown)
                            this.text = replyText
                            if (replyKeyboard != null) replyMarkup = replyKeyboard
                        }
                    }
                messageSender.send(reply as BotApiMethod<Serializable>)
            }
        )

    }

    private fun createVideoInputEnvelope(update: Update): Result<InputEnvelope, String> {
        val result: Result<InputEnvelope?, Throwable> = runCatching {
            val envelope: InputEnvelope? = update.message?.video?.let { vid ->
                val videoDoc = Document(vid.fileId, update.message.caption ?: "", messageSender::download)
                println("Video content=$videoDoc")
                InputEnvelope(
                    objectNode { },
                    objectNode { },
                    update.getTgUser(),
                    contents = InputVideo(videoDoc)
                )
            }
            envelope
        }
        return result.mapError { it.message ?: "" }.flatMap { if (it != null) Ok(it) else Err("No video attached") }
    }

    private fun createPhotoInputEnvelope(update: Update): Result<InputEnvelope, String> {
        val result: Result<InputEnvelope?, Throwable> = runCatching {
            val envelope: InputEnvelope? = update.message?.photo?.let {
                if (it.isNotEmpty()) {
                    val photoContents = it.map {
                        Document(it.fileId, update.message.caption ?: "", messageSender::download)
                    }
                    println("Photo contents=$photoContents")
                    InputEnvelope(
                        objectNode { },
                        objectNode { },
                        update.getTgUser(),
                        contents = InputPhotoList(photoContents)
                    )
                } else null
            }
            envelope
        }
        return result.mapError { it.message ?: ""}.flatMap { if (it != null) Ok(it) else Err("No photo attached") }
    }

    private fun createCommandInputEnvelope(update: Update): Result<InputEnvelope, String> =
        update.message?.text?.let {
            if (it.startsWith("/")) {
                val command = it.removePrefix("/")
                Ok(InputEnvelope(objectNode {}, objectNode {}, update.getTgUser(), command = command, contents = InputCommand(command)))
            } else null
        } ?: return Err("No text message")

    private fun createTextInputEnvelope(update: Update): Result<InputEnvelope, String> =
        update.message?.text?.let {
            if (!it.startsWith("/")) {
                Ok(InputEnvelope(objectNode {}, objectNode {}, update.getTgUser(), contents = InputText(it)))
            } else null
        } ?: return Err("No text message")

    private fun createCallbackInputEnvelope(update: Update): Result<InputEnvelope, String> =
        update.getCallbackJson()?.let {
            val context = it.remove("_") as? ObjectNode ?: objectNode {}
            Ok(InputEnvelope(it, context, update.getTgUser(), contents = InputTransition(it)))
        } ?: return Err("No callback data")

}

private fun Update.getMessageId() = callbackQuery?.message?.messageId ?: message?.messageId ?: -1

private fun Update.getReplyChatId(): Long =
    message?.chatId ?:  callbackQuery?.message?.chatId ?: -1

private fun Update.getCallbackJson() =
    callbackQuery?.data?.let {
        if (it.isNotBlank()) {
            val jsonNode = OBJECT_MAPPER.readTree(it)
            if (jsonNode.isObject) {
                (jsonNode as ObjectNode).deepCopy()
            } else {
                println("Malformed callback json: $jsonNode")
                null
            }
        } else null
    }
private fun Update.getTgUser(): TgUser =
    TgUser(message?.from?.displayName() ?: callbackQuery?.from?.displayName() ?: "",
        message?.from?.id?.toString() ?: callbackQuery?.from?.id?.toString() ?: "",
        message?.from?.userName ?: callbackQuery?.from?.userName ?: "")
