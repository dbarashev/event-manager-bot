package com.bardsoftware.embot

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

const val CB_SECTION = "s"
const val CB_EVENT = "e"
const val CB_COMMAND = "c"

enum class CbSection {
  TEAM, EVENTS;

  val id get() = this.ordinal
}

enum class CbEventCommand {
  LIST, REGISTER, UNREGISTER;
  val id get() = this.ordinal
}
fun processMessage(update: Update, sender: MessageSender) {
  chain(update, sender) {
    val tgUserId = this.userId
    val tgUsername = this.userName
    val tg = this
    val participant = getOrCreateParticipant(tgUserId, tgUsername)

    participant.eventRegistrationCallbacks(this)
    participant.teamManagementCallbacks(this)
    participant.teamMemberDialog(this)
    onCommand("start") {
      val btnTeam = BtnData("Моя команда", """{"$CB_SECTION": ${CbSection.TEAM.id}}""")
      val btnEvents = BtnData("Мои события", """{"$CB_SECTION": ${CbSection.EVENTS.id}}""")
      tg.reply("Привет ${tg.fromUser?.displayName()}!", buttons = listOf(btnTeam, btnEvents))
    }
  }
}

fun getOrCreateParticipant(tgUserId: Long, tgUsername: String): ParticipantRecord =
  txn {
    val tgUserRecord = selectFrom(TGUSER).where(TGUSER.TG_USERID.eq(tgUserId)).fetchOne()
      ?: insertInto(TGUSER).columns(TGUSER.TG_USERID, TGUSER.TG_USERNAME).values(tgUserId, tgUsername)
        .returning().fetchOne()
      ?: throw RuntimeException("Failed to fetch or add user record")

    selectFrom(PARTICIPANT).where(PARTICIPANT.USER_ID.eq(tgUserId)).fetchOne()
      ?: insertInto(PARTICIPANT).columns(PARTICIPANT.USER_ID, PARTICIPANT.DISPLAY_NAME)
          .values(tgUserId, tgUsername)
          .returning()
          .fetchOne()
      ?: throw RuntimeException("Failed to fetch or add participant")
  }

fun findParticipant(id: Int): ParticipantRecord? = db {
  selectFrom(PARTICIPANT).where(PARTICIPANT.ID.eq(id)).fetchOne()
}



