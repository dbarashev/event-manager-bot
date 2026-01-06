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

class TelegramBotsMessageProcessor(private val stateMachine: BotStateMachine) {
    fun processMessage(update: Update, messageSender: MessageSender) {
        createPhotoInputEnvelope(update)
            .orElse { createCommandInputEnvelope(update) }
            .orElse { createCallbackInputEnvelope(update) }
            .orElse { Ok(InputEnvelope(objectNode {}, objectNode {}, update.getTgUser(), contents = InputVoid())) }
            .onSuccess {
                stateMachine.handle(it, createOutputUi(update, it, messageSender))
            }
    }

    private fun createOutputUi(update: Update, inputEnvelope: InputEnvelope, messageSender: MessageSender): OutputUi {
        return OutputUi(
            showButtons = {text, transition ->
                val inplaceUpdate = transition.inplaceUpdate && update.callbackQuery?.message?.messageId != null
                val buttons = transition.buttons.map { (stateId, buttonBuilder) ->
                    stateMachine.getState(stateId)?.let {state ->
                        BtnData(buttonBuilder.label(inputEnvelope), callbackData = json(state.stateJson) {
                            put("_", buttonBuilder.output(inputEnvelope).contextJson)
                        })
                    } ?: throw RuntimeException("State with ID=${stateId} not found")
                }.toList()
                val isMarkdown = text.markup == TextMarkup.MARKDOWN
                val replyText = text.text.ifBlank { "ПУСТОЙ ОТВЕТ" }
                val reply =
                    if (inplaceUpdate) {
                        EditMessageText().apply {
                            messageId = update.getMessageId()
                            chatId = update.getReplyChatId().toString()
                            if (isMarkdown) parseMode = ParseMode.MARKDOWNV2
                            this.text = replyText
                            if (buttons.isNotEmpty()) {
                                replyMarkup = InlineKeyboardMarkup(
                                    buttons.map {
                                        InlineKeyboardButton(it.label).also { btn ->
                                            if (it.callbackData.isNotBlank()) btn.callbackData = it.callbackData
                                        }
                                    }.chunked(transition.columnCount)
                                )
                            }
                        }
                    } else {
                        SendMessage().apply {
                            chatId = update.getReplyChatId().toString()
                            enableMarkdownV2(isMarkdown)
                            this.text = replyText
                            if (buttons.isNotEmpty()) {
                                replyMarkup = InlineKeyboardMarkup(
                                    buttons.map {
                                        InlineKeyboardButton(it.label).also { btn ->
                                            if (it.callbackData.isNotBlank()) btn.callbackData = it.callbackData
                                        }
                                    }.chunked(transition.columnCount)
                                )
                            }
                        }
                    }
                messageSender.send(reply as BotApiMethod<Serializable>)
            }
        )

    }

    private fun createPhotoInputEnvelope(update: Update): Result<InputEnvelope, String> {
        val result: Result<InputEnvelope?, Throwable> = runCatching {
            val envelope: InputEnvelope? = update.message?.photo?.let {
                if (it.isNotEmpty()) {
                    val photoContents = it.map {
                        Document(it.fileId, update.message.caption ?: "", { Err("Not implemented") })
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
                Ok(InputEnvelope(objectNode {}, objectNode {}, update.getTgUser(), command = it.removePrefix("/")))
            } else null
        } ?: return Err("No text message")

    private fun createCallbackInputEnvelope(update: Update): Result<InputEnvelope, String> =
        update.getCallbackJson()?.let {
            val context = it.remove("_") as? ObjectNode ?: objectNode {}
            Ok(InputEnvelope(it, context, update.getTgUser(), contents = InputState(it)))
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
