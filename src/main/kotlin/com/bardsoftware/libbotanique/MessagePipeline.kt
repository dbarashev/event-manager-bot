package com.bardsoftware.libbotanique

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendLocation
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import java.io.Serializable
import java.math.BigDecimal


data class Response(val text: String)
data class BtnData(val label: String, val callbackData: String = "")


typealias CallbackHandler = (ObjectNode) -> Unit
typealias MessageHandler = (String) -> Unit
typealias MatchHandler = (MatchResult) -> Unit

data class Document(val docId: String, val caption: String = "")
data class DocumentList(val docs : List<Document>, val container: Message)

typealias DocumentHandler = (DocumentList) -> Unit

interface MessageSender {
  fun <T: BotApiMethod<Serializable>> send(msg: T)
  fun send(msg: SendMessage)
  fun forward(msg: Message, toChat: String)
  fun sendDoc(doc: SendDocument)
}

private var ourSender: MessageSender = object : MessageSender {
  override fun <T : BotApiMethod<Serializable>> send(msg: T) {
    TODO("Not yet implemented")
  }

  override fun send(msg: SendMessage) {
    TODO("Not yet implemented")
  }

  override fun sendDoc(doc: SendDocument) {
    TODO("Not yet implemented")
  }

  override fun forward(msg: Message, toChat: String) {
    TODO("Not yet implemented")
  }
}

fun getMessageSender() = ourSender
fun setMessageSender(sender: MessageSender) {
  ourSender = sender
}

enum class MessageSource {
  DIRECT
}

open class ChainBuilder(val update: Update, internal val sendMessage: MessageSender, internal val sessionProvider: UserSessionProvider) {
  val messageText = (update.message?.text ?: "").trim()
  val fromUser get() = update.message?.from ?: update.callbackQuery?.from
  val messageId = update.callbackQuery?.message?.messageId ?: update.message?.messageId

  val userId = (this.fromUser?.id ?: -1).toLong()
  val userName get() = this.fromUser?.userName ?: ""
  val chatId = update.message?.chatId

  val dialogState: DialogState? by lazy {
    this.userSession.state
  }
  val userSession: UserSessionStorage get() = this.sessionProvider(userId)

  private var replyChatId = update.message?.chatId ?:  this.update.callbackQuery?.message?.chatId ?: -1

  private val callbackHandlers = mutableListOf<CallbackHandler>()
  private val documentHandlers = mutableListOf<DocumentHandler>()
  private val handlers = mutableListOf<MessageHandler>()
  var stopped = false
  internal val replies = mutableListOf<BotApiMethod<Serializable>>()
  internal val docReplies = mutableListOf<SendDocument>()

  val callbackJson get() = this.update.callbackQuery?.data?.let {
    if (it.isNotBlank()) {
      val jsonNode = OBJECT_MAPPER.readTree(it)
      if (jsonNode.isObject) {
        jsonNode as ObjectNode
      } else {
        println("Malformed callback json: $jsonNode")
        null
      }
    } else null
  }

  fun parseJson(code: (ObjectNode) -> Unit) {
    try {
      this.callbackJson?.let(code)
    } catch (ex: JsonProcessingException) {
      ex.printStackTrace()
    }
  }

  fun onCallback(code: CallbackHandler) {
    this.callbackHandlers += code
  }

  fun onDocument(whenState: Int? = null, code: DocumentHandler) {
    this.documentHandlers += {docs ->
      if (whenState == null || this.dialogState?.state == whenState) {
        code(docs)
      }
    }
  }

  fun onCommand(vararg commands: String, messageSource: MessageSource = MessageSource.DIRECT, executeImmediately: Boolean = true, code: MessageHandler) {
    if (messageSource == MessageSource.DIRECT && update.message?.chatId != update.message?.from?.id) {
      return
    }
    fun execute(msg: String) {
      commands.forEach { command ->
        val slashedCommand = "/$command"
        if (msg.lowercase().startsWith(slashedCommand)) {
          code(msg.substring(slashedCommand.length).trim())
        }
      }
    }
    if (executeImmediately && this.messageText.isNotBlank()) {
      execute(this.messageText)
    } else {
      this.handlers += ::execute
    }
  }

  fun onText(text: String, whenState: Int? = null, code: MessageHandler) {
    this.handlers += { msg ->
      if (msg == text) {
        if (whenState == null || this.dialogState?.state == whenState) {
          code(msg)
        }
      }
    }
  }

  fun onInput(whenState: Int, code: MessageHandler) {
    this.handlers += {msg ->
      if (this.userSession.state?.state == whenState) {
        code(msg)
      }
    }
  }

  fun onRegexp(pattern: String, options: Set<RegexOption> = setOf(RegexOption.MULTILINE), whenState: Int? = null,
               messageSource: MessageSource = MessageSource.DIRECT, code: MatchHandler
  ) {
    if (messageSource == MessageSource.DIRECT && update.message?.chatId != update.message?.from?.id) {
      return
    }
    val regexp = pattern.toRegex(options)
    this.handlers += { msg ->
      regexp.matchEntire(msg.trim().replace("\n", ""))?.let {
        if (whenState == null || this.dialogState?.state == whenState) {
          code(it)
        } else {
          println("whenState=$whenState does not match the dialog state=${this.dialogState}")
        }

      }
    }
  }

  fun replyDocument(doc: String) {
    docReplies.add(SendDocument().also {
      it.document = InputFile(doc.byteInputStream(), "data.csv")
      it.chatId = this.update.message.chatId.toString()
    })
  }

  fun sendLocation(lat: BigDecimal, lon: BigDecimal) {
    replies.add(SendLocation().apply {
      chatId = this@ChainBuilder.replyChatId.toString()
      latitude = lat.toDouble()
      longitude = lon.toDouble()
    } as BotApiMethod<Serializable>)
  }

  fun createMessage(
    msg: String, buttons: List<BtnData> = listOf(), maxCols: Int = Int.MAX_VALUE,
    isMarkdown: Boolean = false, isInlineKeyboard: Boolean = true)  =
      SendMessage().apply {
        enableMarkdownV2(isMarkdown)
        text = msg.ifBlank { "ПУСТОЙ ОТВЕТ" }
        if (buttons.isNotEmpty()) {
          if (isInlineKeyboard) {
            replyMarkup = InlineKeyboardMarkup(
              buttons.map {
                InlineKeyboardButton(it.label).also { btn ->
                  if (it.callbackData.isNotBlank()) btn.callbackData = it.callbackData
                }
              }.chunked(maxCols)
            )
          } else {
            replyMarkup = ReplyKeyboardMarkup().also {
              it.keyboard = buttons.map { KeyboardButton(it.label) }.chunked(maxCols).map { KeyboardRow(it) }
              it.resizeKeyboard = true
            }
          }
        }
      }

  fun reply(msg: String, stop: Boolean = false,
            buttons: List<BtnData> = listOf(),
            maxCols: Int = Int.MAX_VALUE,
            isMarkdown: Boolean = false,
            isInplaceUpdate: Boolean = false,
            isInlineKeyboard: Boolean = true) {
    if (!isInplaceUpdate) {
      replies.add(createMessage(msg, buttons, maxCols, isMarkdown, isInlineKeyboard).apply {
        chatId = this@ChainBuilder.replyChatId.toString()
      }  as BotApiMethod<Serializable>)
      this.stopped = this.stopped || stop
    } else {
      replies.add(EditMessageText().apply {
        messageId = this@ChainBuilder.messageId
        chatId = this@ChainBuilder.replyChatId.toString()
        if (isMarkdown) parseMode = ParseMode.MARKDOWNV2
        text = msg.ifBlank { "ПУСТОЙ ОТВЕТ" }
        if (buttons.isNotEmpty()) {
          replyMarkup = InlineKeyboardMarkup(
              buttons.map {
                InlineKeyboardButton(it.label).also { btn ->
                  if (it.callbackData.isNotBlank()) btn.callbackData = it.callbackData
                }
              }.chunked(maxCols)
          )
        }
      })
    }
  }

  fun stop() {
    this.stopped = true
  }

  fun handle(): List<out BotApiMethod<Serializable>> {
    try {
      when {
        !(this.update.message?.photo?.isNullOrEmpty() ?: true) -> {
          val docs = DocumentList(this.update.message.photo.map {
            Document(it.fileId, this.update.message.caption ?: "")
          }.toList(), this.update.message)

          for (h in documentHandlers) {
            h(docs)
            if (this.stopped) {
              break
            }
          }
        }
        this.update.message?.document != null -> {
          val docs = DocumentList(listOf(Document(this.update.message.document.fileId, this.update.message.caption ?: "")), this.update.message)
          for (h in documentHandlers) {
            h(docs)
            if (this.stopped) {
              break
            }
          }
        }
        this.update.callbackQuery != null -> {
          this.replyChatId = this.update.callbackQuery.message.chatId
          this.update.callbackQuery.let { q ->
            AnswerCallbackQuery().apply {
              callbackQueryId = q.id
            }.also { sendMessage.send(it as BotApiMethod<Serializable>) }

            parseJson {json ->
              for (h in callbackHandlers) {
                h(json)
                if (this.stopped) {
                  break
                }
              }
            }
          }
        }

        this.messageText.isNotBlank() -> {
          for (h in handlers) {
            h(this.messageText)
            if (this.stopped) {
              break
            }
          }
        }

        else -> {}
      }
    } catch (ex: Exception) {
      ex.printStackTrace()
      reply("Что-то сломалось внутри бота", isMarkdown = false)
    }
    return replies
  }

  fun withUser(code: (ChainBuilder.(user: User) -> Unit)) {
    this.update.message.from?.let {
      code(this, it)
    }
  }

}

fun chain(update: Update,
          sender: MessageSender,
          sessionProvider: UserSessionProvider = ::userSessionProviderMem,
          handlers: (ChainBuilder.() -> Unit)) {
  try {
    ChainBuilder(update, sender, sessionProvider).apply(handlers).also {
      val replies = it.handle()
      it.sendReplies()
    }
  } catch (ex: Exception) {
    ex.printStackTrace()
  }
}

fun ChainBuilder.sendReplies() {
  replies.forEach { reply ->
    try {
      sendMessage.send(reply)
    } catch (ex: TelegramApiRequestException) {
      ex.printStackTrace()
      when (reply) {
        is SendMessage -> {
          println("Failed to send message to ${reply.chatId}")
          println("""Message:
                |
                |$reply
              """.trimMargin())
          sendMessage.send(
            SendMessage(
              reply.chatId,
              "Что-то сломалось при отправке ответа."
            ) as BotApiMethod<Serializable>
          )
        }

        else -> println(reply)
      }
    }
  }
  docReplies.forEach { doc ->
    try {
      sendMessage.sendDoc(doc)
    } catch (ex: TelegramApiRequestException) {
      ex.printStackTrace()
    }
  }
}

private val escapedChars = charArrayOf('_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!')
private val ESCAPER = object : CharEscaper() {
  override fun escape(c: Char): CharArray {
    return if (escapedChars.contains(c)) charArrayOf('\\', c) else charArrayOf(c)
  }
}

fun (String).escapeMarkdown() = ESCAPER.escape(this)
fun (BigDecimal).escapeMarkdown() = this.toString().escapeMarkdown()

fun (List<BigDecimal>).escapeMarkdown() = this.map { it.escapeMarkdown() }.joinToString(separator = ", ")

fun (ArrayNode).item(builder: ObjectNode.() -> Unit) {
  this.add(OBJECT_MAPPER.createObjectNode().also(builder))
}

fun String.asJson() = jacksonObjectMapper().readTree(this) as? ObjectNode
val OBJECT_MAPPER = ObjectMapper()

data class DialogState(val state: Int, val data: String?) {
  fun asJson(): ObjectNode? {
    try {
      return data?.asJson()
    } catch (ex: JsonParseException) {
      println("""Failed to parse:
        |$data
      """.trimMargin())
      ex.printStackTrace()
      throw RuntimeException(ex)
    }
  }
}

fun User.displayName(): String = "${this.firstName} ${this.lastName ?: ""}"

interface UserSessionStorage {
  val state: DialogState?
  fun reset()
  fun save(stateId: Int, data: String)
}

typealias UserSessionProvider = (Long) -> UserSessionStorage

class UserSessionStorageMem: UserSessionStorage {
  private var stateImpl = DialogState(-1, "")
  override val state: DialogState?
    get() = stateImpl

  override fun reset() {
    stateImpl = DialogState(-1, "")
  }

  override fun save(stateId: Int, data: String) {
    stateImpl = DialogState(stateId, data)
  }
}

private val userSessionMap = mutableMapOf<Long, UserSessionStorageMem>()
fun userSessionProviderMem(tgUserId: Long) = userSessionMap.computeIfAbsent(tgUserId) { UserSessionStorageMem() }