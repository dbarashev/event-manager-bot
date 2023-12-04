package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import org.slf4j.LoggerFactory
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
  LANDING, MANAGER, PARTICIPANT, TEAM, EVENTS, SETTINGS, DIALOG;

  val id get() = this.ordinal
}

enum class CbEventCommand {
  LANDING, LIST, REGISTER, UNREGISTER;
  val id get() = this.ordinal
}

fun buildStateMachine(): BotStateMachine = BotStateMachine().apply {
  state("START") {
    trigger {
      setSection(CbSection.LANDING)
    }
    action(id) {input -> Ok(SimpleStateAction(
      text = TextMessage("Привет, ${input.user.displayName}!"),
      buttons = listOf(
        "ORG_LANDING" to         ButtonBuilder(label = { "Организатор >>" }),
        "PARTICIPANT_LANDING" to ButtonBuilder(label = { "Участник >>" }),
        "SETTINGS_LANDING" to    ButtonBuilder(label = { "Настройки >>" })
      )))
    }
  }

  state("ORG_LANDING") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.LANDING)
    }
    action(id) {
      Ok(OrgLandingAction(it))
    }
  }

  state("ORG_EVENT_INFO") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
    }
    action(id) {
      Ok(OrgEventInfoAction(it))
    }
  }
  state("PARTICIPANT_LANDING") {
    trigger {
      setSection(CbSection.PARTICIPANT)
      setCommand(CbEventCommand.LANDING)
    }
  }
  state("SETTINGS_LANDING") {
    trigger {
      setSection(CbSection.SETTINGS)
      setCommand(SettingsCommand.LANDING)
    }
  }
  state("ORG_EVENT_ADD") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ADD)
    }
  }
  state("ORG_EVENT_INFO") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
    }
  }
  state("ORG_EVENT_ARCHIVE") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ARCHIVE)
    }
  }
  state("ORG_EVENT_EDIT") {
    isIgnored = true
    trigger {
      setSection(CbSection.DIALOG)
      setCommand(OrgManagerCommand.EVENT_EDIT)
    }
  }
  state("ORG_EVENT_DELETE") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_DELETE)
    }
  }
  state("ORG_SETTINGS") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.ORG_SETTINGS)
    }
  }

}


fun processMessage(update: Update, sender: MessageSender) {
  LOG_INPUT.debug("Received update {}", update.message?.messageId ?: update.callbackQuery?.let {"callback"})
  val tg = ChainBuilder(update, sender, ::userSessionProvider)
  val user = TgUser(tg.fromUser?.displayName() ?: "", tg.fromUser?.id?.toString() ?: "")
  buildStateMachine().run {
    val inputData = if (update.message?.text == "/start") {
      InputData(objectNode { setSection(CbSection.LANDING) }, objectNode {  }, user)
    } else {
      tg.callbackJson?.let {
        val contextJson = it.remove("_") as? ObjectNode ?: objectNode {}
        InputData(it, contextJson = contextJson, user)
      } ?: InputData(objectNode {}, objectNode {}, user)
    }
    val outputUi = createOutputUi(tg, inputData, this::getState)

    handle(inputData, outputUi)
  }.onSuccess { tg.sendReplies() }.onFailure {
    tg.callbackJson?.let {callbackJson ->
      val contextJson = callbackJson.remove("_")
      LOG_INPUT.debug("Callback JSON={}", callbackJson)
      LOG_INPUT.debug("Context JSON={}", contextJson)
      if (contextJson is ObjectNode) {
        callbackJson.setAll<ObjectNode>(contextJson)
      }
      tg.update.callbackQuery?.data = callbackJson.toString()
    }

    LOG_INPUT.debug("Merged callback JSON: {}", tg.callbackJson)
    chain(update, sender, ::userSessionProvider) {
      val tgUserId = this.userId
      val tgUsername = this.userName
      val participant = getOrCreateParticipant(tgUserId, tgUsername, 1)

      participant.landing(this)
      participant.organizationManagementCallbacks(this)
      participant.eventRegistrationCallbacks(this)
      participant.teamManagementCallbacks(this)
      participant.settingsModule(this)

    }
  }
}

fun getOrCreateParticipant(tgUserId: Long, tgUsername: String, orgId: Int): ParticipantRecord =
  txn {
    val tgUserRecord = selectFrom(TGUSER).where(TGUSER.TG_USERID.eq(tgUserId)).fetchOne()
      ?: insertInto(TGUSER).columns(TGUSER.TG_USERID, TGUSER.TG_USERNAME).values(tgUserId, tgUsername)
        .onConflict(TGUSER.TG_USERNAME).doUpdate().set(TGUSER.TG_USERID, tgUserId)
        .returning()
        .fetchOne()
      ?: throw RuntimeException("Failed to fetch or add user record")

    selectFrom(PARTICIPANT).where(PARTICIPANT.USER_ID.eq(tgUserId)).fetchOne()
      ?: run {
        val participant = insertInto(PARTICIPANT).columns(PARTICIPANT.USER_ID, PARTICIPANT.DISPLAY_NAME)
          .values(tgUserId, tgUsername)
          .returning()
          .fetchOne()
        getDefaultSeries(orgId)?.let {
          insertInto(EVENTSERIESSUBSCRIPTION)
            .columns(EVENTSERIESSUBSCRIPTION.SERIES_ID, EVENTSERIESSUBSCRIPTION.PARTICIPANT_ID)
            .values(it.id!!, participant!!.id!!)
            .execute()
        }
        participant
      } ?: throw RuntimeException("Failed to fetch or add participant")
  }

fun findParticipant(id: Int): ParticipantRecord? = db {
  selectFrom(PARTICIPANT).where(PARTICIPANT.ID.eq(id)).fetchOne()
}

fun ObjectNode.getSection() = this[CB_SECTION]?.asInt()?.let(CbSection.values()::get) ?: CbSection.LANDING
fun ObjectNode.setSection(section: CbSection) = this.apply {
  put(CB_SECTION, section.id)
}

private val LOG_INPUT = LoggerFactory.getLogger("Bot.Input")