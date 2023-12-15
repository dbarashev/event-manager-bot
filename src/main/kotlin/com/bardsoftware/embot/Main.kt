package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.JsonNode
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

enum class EMBotState(val id: Int) {
  PARTICIPANT_SHOW_EVENT(200), PARTICIPANT_UNREGISTER(202);

  val code: String = "$id"
  companion object {
    fun byId(id: Int) = entries.find { it.id == id }
  }
}

private fun BotStateMachine.state(state: EMBotState, code: State.()->Unit) = this.state(state.id, code)
fun buildStateMachine(): BotStateMachine = BotStateMachine().apply {
  state("START") {
    trigger {
      setSection(CbSection.LANDING)
    }
    menu {
      text = "Привет, ${it.user.displayName}!"
      buttons(
        "Организатор >>" to "ORG_LANDING",
        "Участник >>"    to "PARTICIPANT_LANDING",
        "Настройки >>"   to "SETTINGS_LANDING"
      )
    }
  }

  state("ORG_LANDING") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.LANDING)
    }
    action(::OrgLandingAction)
  }

  state("ORG_EVENT_INFO") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
    }
    action(::OrgEventInfoAction)
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
  state("ORG_EVENT_ARCHIVE") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ARCHIVE)
    }
    action { SimpleAction("Событие перемещено в архив", "ORG_LANDING", it) {
      it.contextJson.getEventId()!!.let(::archiveEvent)
    }}
  }
  state("ORG_EVENT_ADD") {
    isIgnored = true
    trigger {
      setSection(CbSection.DIALOG)
      setDialogId(OrgManagerCommand.EVENT_ADD.id)
    }
  }
  state("ORG_EVENT_EDIT") {
    isIgnored = true
    trigger {
      setSection(CbSection.DIALOG)
      setDialogId(OrgManagerCommand.EVENT_EDIT.id)
    }
  }
  state("ORG_EVENT_PARTICIPANT_LIST") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_PARTICIPANT_LIST)
    }
    action(::OrgEventParticipantListAction)
  }
  state("ORG_EVENT_PARTICIPANT_ADD") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_PARTICIPANT_ADD)
    }
  }
  state("ORG_EVENT_PARTICIPANT_INFO") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_PARTICIPANT_INFO)
    }
    menu {
      text = "Здесь вы можете отменить регистрацию участника"
      buttons(
        "Отменить регистрацию" to "ORG_EVENT_PARTICIPANT_DELETE",
        "<< Назад"             to "ORG_EVENT_PARTICIPANT_LIST",
      )
    }
  }
  state("ORG_EVENT_PARTICIPANT_DELETE") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_PARTICIPANT_DELETE)
    }
    action { SimpleAction("Регистрация участника отменена", "ORG_EVENT_PARTICIPANT_LIST", it) {
      val eventId = it.contextJson.getEventId() ?: return@SimpleAction
      val participantId = it.contextJson.getParticipantId() ?: return@SimpleAction
      unregisterParticipant(eventId, participantId)
    }}
  }
  state("ORG_EVENT_DELETE") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_DELETE)
    }
    action {SimpleAction("Событие перемещено в помойку", "ORG_LANDING", it) {
      it.contextJson.getEventId()!!.let(::deleteEvent)
    }}
  }
  state("ORG_SETTINGS") {
    trigger {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.ORG_SETTINGS)
    }
  }
  state("PARTICIPANT_LANDING") {
    trigger {
      setSection(CbSection.PARTICIPANT)
      setCommand(CbEventCommand.LANDING)
    }
    menu {
      markdown = """
        *Кабинет участника мероприятия*
        ${"—".repeat(20)}
        Что тут можно делать?
    
        *Моя команда* — тут можно добавить участника мероприятий, у которого нет своего телеграма, например вашего ребенка\.
        *Мои события* — тут можно регистрироваться на доступные вам мероприятия\.
      """.trimIndent()
      buttons(
        "Моя команда >>" to "TEAM_LANDING",
        "Мои события >>" to "PARTICIPANT_EVENTS",
        "<< Назад"       to "START"
      )
    }
  }
  state("TEAM_LANDING") {
    trigger {
      setSection(CbSection.TEAM)
      setCommand(CbTeamCommand.LANDING)
    }
    action(::TeamLandingAction)
  }
  state("TEAM_MEMBER_ADD") {
    isIgnored = true
    trigger {
      setSection(CbSection.DIALOG)
      setCommand(CbTeamCommand.ADD_DIALOG)
    }
  }
  state("TEAM_MEMBER_INFO") {
    trigger {
      setSection(CbSection.TEAM)
      setCommand(CbTeamCommand.MEMBER_INFO)
    }
    action(::TeamMemberInfoAction)
  }
  state("TEAM_EDIT_DIALOG") {
    isIgnored = true
    trigger {
      setSection(CbSection.DIALOG)
      setCommand(CbTeamCommand.EDIT_DIALOG)
    }
  }
  state("TEAM_MEMBER_DELETE") {
    trigger {
      setSection(CbSection.TEAM)
      setCommand(CbTeamCommand.MEMBER_DELETE)
    }
    action { SimpleAction(
      "Товарищ вычеркнут из команды. Информация о его мероприятиях осталась в системе.",
      "TEAM_LANDING", it) {
      val memberId = it.contextJson.getTeamMemberId() ?: return@SimpleAction
      val leaderId = it.contextJson.getTeamLeaderId() ?: return@SimpleAction
      deleteTeamMember(memberId, leaderId)
    }}
  }
  state("PARTICIPANT_EVENTS") {
    trigger {
      setSection(CbSection.EVENTS)
      setCommand(CbEventCommand.LANDING)
    }
    action(::ParticipantEventsAction)
  }
  state(EMBotState.PARTICIPANT_SHOW_EVENT) {
    action(::ParticipantShowEventAction)
  }

  state("PARTICIPANT_REGISTER") {
    isIgnored = true
    trigger {
      setSection(CbSection.EVENTS)
      setCommand(CbEventCommand.REGISTER)
      isSubset = true
    }
  }
  state(EMBotState.PARTICIPANT_UNREGISTER) {
    action(::ParticipantUnregisterAction)
  }

}


fun processMessage(update: Update, sender: MessageSender) {
  LOG_INPUT.debug("Received update {}", update.message?.messageId ?: update.callbackQuery?.let {"callback ${it.data}"})
  val tg = ChainBuilder(update, sender, ::userSessionProvider)
  val user = TgUser(displayName = tg.fromUser?.displayName() ?: "", id = tg.fromUser?.id?.toString() ?: "", username = tg.fromUser?.userName ?: "")

  buildStateMachine().run {
    val inputData = update.message?.text?.let {
      if (it == "/start") {
        InputData(objectNode { setSection(CbSection.LANDING) }, objectNode {  }, user)
      } else if (it.startsWith("/start")) {
        it.removePrefix("/start").trim().toIntOrNull()?.let {eventId ->
          InputData(this.getState(EMBotState.PARTICIPANT_SHOW_EVENT.code)?.stateJson ?: objectNode {},
            objectNode { setEventId(eventId) },
            user
          )
        }
      } else null
    } ?:
      tg.callbackJson?.let {
        val contextJson = it.remove("_") as? ObjectNode ?: objectNode {}
        InputData(it, contextJson = contextJson, user)
      } ?: InputData(objectNode {}, objectNode {}, user)

    val outputUi = createOutputUi(tg, inputData, this::getState)

    handle(inputData, outputUi)
  }.onSuccess { tg.sendReplies() }.onFailure {
    LOG_INPUT.warn("Failed to process with the state machine: {}", it)
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

      organizationManagementModule(this)
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