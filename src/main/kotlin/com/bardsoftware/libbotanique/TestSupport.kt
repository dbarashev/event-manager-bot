package com.bardsoftware.libbotanique

import com.github.michaelbull.result.Result
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Message
import java.io.Serializable

class TestMessageSender(private val destChatId: String,
                        private val delegate: MessageSender): MessageSender {
  override fun <T : BotApiMethod<Serializable>> send(msg: T) {
    if (msg is SendMessage) {
      msg.chatId = destChatId
    }
    delegate.send(msg)
  }

  override fun send(msg: SendMessage) {
    msg.chatId = destChatId
    delegate.send(msg)
  }

  override fun forward(msg: Message, toChat: String) {
    delegate.forward(msg, toChat)
  }

  override fun sendDoc(doc: SendDocument) {
    delegate.sendDoc(doc)
  }

  override fun download(doc: Document): Result<ByteArray, String> {
    return delegate.download(doc)
  }

}