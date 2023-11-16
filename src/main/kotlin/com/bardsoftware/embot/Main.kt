package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook

fun main(args: Array<String>) {
  if (args.isNotEmpty() && args[0] == "poll") {
    TelegramBotsApi(DefaultBotSession::class.java).registerBot(LongPollingConnector(::processMessage, testReplyChatId = null, testBecome = null))
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
  LANDING, MANAGER, PARTICIPANT, TEAM, EVENTS;

  val id get() = this.ordinal
}

enum class CbEventCommand {
  LIST, REGISTER, UNREGISTER;
  val id get() = this.ordinal
}
fun processMessage(update: Update, sender: MessageSender) {
  chain(update, sender, ::userSessionProvider) {
    val tgUserId = this.userId
    val tgUsername = this.userName
    val tg = this
    val participant = getOrCreateParticipant(tgUserId, tgUsername)

    participant.landing(this)
    participant.organizationManagementCallbacks(this)
    participant.eventRegistrationCallbacks(this)
    participant.teamManagementCallbacks(this)
    participant.teamMemberDialog(this)
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

fun ObjectNode.getSection() = this[CB_SECTION]?.asInt()?.let(CbSection.values()::get) ?: CbSection.LANDING
fun ObjectNode.setSection(section: CbSection) = this.apply {
  removeAll()
  put(CB_SECTION, section.id)
}

