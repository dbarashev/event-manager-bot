package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook

fun main(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "poll") {
    TelegramBotsApi(DefaultBotSession::class.java).registerBot(LongPollingConnector(::processMessage))
  } else {
    TelegramBotsApi(DefaultBotSession::class.java, DefaultWebhook().also {
      it.setInternalUrl("http://0.0.0.0:8080")
    }).registerBot(WebHookConnector(::processMessage), SetWebhook(System.getenv("TG_BOT_URL")))
  }
}

fun processMessage(update: Update, sender: MessageSender) {
  chain(update, sender) {
    val tgUserId = this.userId
    val tgUsername = this.userName
    val tg = this
    val participant = getOrCreateParticipant(tgUserId, tgUsername)

    onCallback { node ->
      val event = node["e"]?.asInt()?.let { getEvent(it) } ?: return@onCallback
      val command = node["c"]?.asInt() ?: return@onCallback
      when(command) {
        0 -> {
          val btns = if (event.id in getAvailableEvents(participant).map { it.id }) {
            listOf(BtnData("Зарегистрироваться", """{"e": ${event.id}, "c": 1}"""))
          } else listOf(BtnData("Отменить регистрацию", """{"e": ${event.id}, "c": 2}"""))
          tg.reply(
            """*${event.title!!.escapeMarkdown()}*
              |${event.seriesTitle?.escapeMarkdown() ?: ""}
              | ${"\\-".repeat(20)}
              | *Организаторы*\: ${event.organizerTitle?.escapeMarkdown() ?: ""}
              | *Дата*\: ${event.start.toString().escapeMarkdown()}
            """.trimMargin(), isMarkdown = true, buttons = btns)
        }
        1 -> {
          event.register(participant)
          tg.reply("Вы зарегистрированы!", buttons = participant.getEventButtons(), maxCols = 1)
        }
        2 -> {
          event.unregister(participant)
          tg.reply("Регистрация отменена", buttons = participant.getEventButtons(), maxCols = 1)
        }
      }
    }
    onCommand("start") {
      tg.reply("Привет ${tg.fromUser?.displayName()}!")
      tg.reply("We have some events for you:", buttons = participant.getEventButtons(), maxCols = 1)
    }
  }
}

fun getOrCreateParticipant(tgUserId: Long, tgUsername: String): ParticipantRecord =
  txn {
    val tgUserRecord = selectFrom(TGUSER).where(TGUSER.TG_USERID.eq(tgUserId)).fetchOne()
      ?: insertInto(TGUSER).columns(TGUSER.TG_USERID, TGUSER.TG_USERNAME).values(tgUserId, tgUsername)
        .returning().fetchOne()
      ?: throw RuntimeException("Failed to fetch or add user record")

    selectFrom(PARTICIPANT).where(PARTICIPANT.USER_ID.eq(tgUserRecord.id)).fetchOne()
      ?: insertInto(PARTICIPANT).columns(PARTICIPANT.USER_ID, PARTICIPANT.DISPLAY_NAME)
          .values(tgUserRecord.id, tgUsername)
          .returning()
          .fetchOne()
      ?: throw RuntimeException("Failed to fetch or add participant")
  }


fun getEvent(id: Int): EventviewRecord? =
  db {
    selectFrom(EVENTVIEW).where(EVENTVIEW.ID.eq(id)).fetchOne()
  }
fun getRegisteredEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    selectFrom(EVENT).where(EVENT.ID.`in`(
        select(EVENTREGISTRATION.EVENT_ID)
        .from(EVENTREGISTRATION)
        .where(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id!!)))
    ).toList()
  }
fun getAvailableEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    selectFrom(EVENT).where(EVENT.ID.notIn(
      select(EVENTREGISTRATION.EVENT_ID)
        .from(EVENTREGISTRATION)
        .where(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id!!)))
    ).toList()
  }

fun ParticipantRecord.getEventButtons() =
  (getRegisteredEvents(this).map {
    BtnData(it.formatCheckedLabel(), """{"e": ${it.id}, "c": 0}""")
  } + getAvailableEvents(this).map {
    BtnData(it.formatUncheckedLabel(), """{"e": ${it.id}, "c": 0}""")
  }).toList()

fun EventRecord.formatCheckedLabel() = """✔ ${this.title} / ${this.start}"""
fun EventRecord.formatUncheckedLabel() = """☐ ${this.title} / ${this.start}"""

fun EventviewRecord.register(participant: ParticipantRecord) = db {
  insertInto(EVENTREGISTRATION).columns(EVENTREGISTRATION.PARTICIPANT_ID, EVENTREGISTRATION.EVENT_ID)
    .values(participant.id, this@register.id).execute()
}

fun EventviewRecord.unregister(participant: ParticipantRecord) = db {
  deleteFrom(EVENTREGISTRATION).where(
    EVENTREGISTRATION.EVENT_ID.eq(this@unregister.id)
      .and(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id))
  ).execute()
}