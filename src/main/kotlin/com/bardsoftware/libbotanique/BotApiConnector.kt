package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.runCatching
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.bots.TelegramWebhookBot
import org.telegram.telegrambots.meta.ApiConstants
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.ForwardMessage
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.io.Serializable
import java.net.URI

typealias MessageProcessor = (update: Update, sender: MessageSender) -> Unit

class LongPollingConnector(
    private val processor: MessageProcessor,
    testReplyChatId: String?,
    private val testBecome: String?) :
  TelegramLongPollingBot(createBotOptions(), System.getenv("TG_BOT_TOKEN") ?: ""),
  MessageSender {

  init {
    println("Started SQooL 2023 under name $botUsername")
    if (!testReplyChatId.isNullOrBlank()) {
      println("We will send all replies to $testReplyChatId")
    }
    setMessageSender(testReplyChatId?.let {TestMessageSender(it, this)} ?: this )
  }
  override fun getBotUsername() = System.getenv("TG_BOT_USERNAME") ?: "sqool_bot"
  override fun <T : BotApiMethod<Serializable>> send(msg: T) {
      execute(msg)
  }

  override fun send(msg: SendMessage) {
    execute(msg)
  }

  override fun sendDoc(doc: SendDocument) {
    execute(doc)
  }

  override fun forward(msg: Message, toChat: String) {
    execute(ForwardMessage().also {
      it.chatId = toChat
      it.fromChatId = msg.chatId.toString()
      it.messageId = msg.messageId
    })
  }

  override fun onUpdateReceived(update: Update) {
    testBecome?.split(",")?.forEach { pair ->
      val (source, become) = pair.split(":")
      source.toLongOrNull()?.let {sourceId ->
        if (update.message?.chatId == sourceId) {
          update.message.chat.id = become.toLong()
          update.message.from.id = become.toLong()
        }
        if (update.callbackQuery?.from?.id == sourceId) {
          update.callbackQuery.from.id = become.toLong()
        }
      } ?: run {
        if (update.message?.from?.userName == source) {
          update.message.from.userName = become
        }
        if (update.callbackQuery?.from?.userName == source) {
          update.callbackQuery.from.userName = become
        }
      }
    }
    try {
      processor(update, getMessageSender())
    } catch (ex: Exception) {
      LOG.error("Failed to process the update", ex)
      LOG.debug("Update data: {}", update)
      update.message?.let {
        getMessageSender().send(SendMessage(it.chatId.toString(), "Oooops!"))
      }
    }
  }

  fun download(document: Document): Result<ByteArray, String> {
    GetFile().apply {
      fileId = document.docId
      return execute(this)?.let {file ->
        val downloadUrl = "https://api.telegram.org/file/bot${this@LongPollingConnector.botToken}/${file.filePath}"
        runCatching { URI(downloadUrl).toURL().readBytes() }.mapError { it.message ?: "Unknown error" }
      } ?: Err("Failed to download the file")
    }
  }
}

class WebHookConnector(private val processor: MessageProcessor) :
  TelegramWebhookBot(System.getenv("TG_BOT_TOKEN") ?: ""),
  MessageSender {
  override fun getBotUsername() = System.getenv("TG_BOT_USERNAME") ?: ""
  override fun getBotPath(): String = System.getenv("TG_BOT_PATH") ?: ""

  init {
    println("Started SQooL 2022 under name $botUsername")
    setMessageSender(this)
  }

  override fun onWebhookUpdateReceived(update: Update): BotApiMethod<*>? {
    return try {
      LOGGER.info("Received update: $update")
      processor(update, this)
      null
    } catch (ex: Exception) {
      LOGGER.error("Failed to process update", ex)
      SendMessage().apply {
        chatId = update.message?.chatId?.toString() ?: ""
        text = "ERROR"

      }
    }
  }

  override fun <T : BotApiMethod<Serializable>> send(msg: T) {
    execute(msg)
  }

  override fun send(msg: SendMessage) {
    execute(msg)
  }

  override fun sendDoc(doc: SendDocument) {
    execute(doc)
  }

  override fun forward(msg: Message, toChat: String) {
    execute(ForwardMessage().also {
      it.chatId = toChat
      it.fromChatId = msg.chatId.toString()
      it.messageId = msg.messageId
    })
  }
}

private fun createBotOptions() = DefaultBotOptions().apply {
  baseUrl = System.getenv("TG_BASE_URL") ?: ApiConstants.BASE_URL
}
private val LOGGER = LoggerFactory.getLogger("Bot")

fun emptyNode() = jacksonObjectMapper().createObjectNode()
fun createOutputUi(tg: ChainBuilder, inputEnvelope: InputEnvelope, state: (String)->State?): OutputUi {
  return OutputUi(
    showButtons = {text, transition ->
      tg.reply(text.text, isMarkdown = text.markup == TextMarkup.MARKDOWN, maxCols = transition.columnCount,
        isInplaceUpdate = transition.inplaceUpdate && tg.update.callbackQuery?.message?.messageId != null,
        buttons = transition.buttons.mapNotNull { (stateId, buttonBuilder) ->
          state(stateId)?.let {state ->
            BtnData(buttonBuilder.label, callbackData = json(state.stateJson) {
              put("_", buttonBuilder.output(inputEnvelope).contextJson)
            })
          } ?: throw RuntimeException("State with ID=${stateId} not found")
        }.toList()
      )
    }
  )
}

internal fun json(prototype: ObjectNode = jacksonObjectMapper().createObjectNode(),
                  builder: ObjectNode.() -> Unit) = prototype.deepCopy().apply(builder).toString()

private val LOG = LoggerFactory.getLogger("Bot")