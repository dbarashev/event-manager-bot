package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jooq.impl.DSL
import org.jooq.types.DayToSecond
import java.time.Duration

class ParticipantEventsAction(private val participant: ParticipantRecord): StateAction {
  constructor(inputData: InputData): this(inputData.getOrCreateParticipant())
  override val text get() = TextMessage("Ваши события:")
  override val buttonTransition get() = ButtonTransition(
    buttons = getAvailableEvents(participant).map { eventRecord ->
      EMBotState.PARTICIPANT_SHOW_EVENT.code to ButtonBuilder({ eventRecord.formatUncheckedLabel() }) {
        OutputData(objectNode {
          setEventId(eventRecord.id!!)
        }
      )}
    }.toList() + listOf(
      "PARTICIPANT_LANDING" to ButtonBuilder({"\ud83d\udd19 Назад"})
    )
  )
}

class ParticipantShowEventAction(
  private val event: EventviewRecord,
  private val registeredTeam: List<EventteamregistrationviewRecord>): StateAction {

  constructor(inputData: InputData): this(
    event = inputData.contextJson.getEventId()?.let(::getEventRecord) ?: throw IllegalArgumentException("Event ID not found in the input"),
    registeredTeam = db {
      selectFrom(EVENTTEAMREGISTRATIONVIEW).where(
        EVENTTEAMREGISTRATIONVIEW.REGISTRANT_TGUSERID.eq(inputData.user.id.toLong())
          .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(inputData.contextJson.getEventId() ?: throw IllegalArgumentException("Event ID not found in the input")))
      ).toList()
    }
  )

  override val text: TextMessage
    get() {
      val registeredLabel =
        if (registeredTeam.isEmpty()) "из вашей команды пока никто"
        else registeredTeam.map { it.participantName!!.escapeMarkdown() }.joinToString(separator = ", ")
      return TextMessage(event.formatDescription(registeredLabel, isOrg = false), TextMarkup.MARKDOWN)
    }

  override val buttonTransition = ButtonTransition(
    buttons = listOf(
      "PARTICIPANT_REGISTER" to ButtonBuilder({"Регистрация \u23E9"}) {
        OutputData(objectNode { setEventId(event.id!!) })
      }) + if (registeredTeam.isEmpty()) emptyList() else listOf(
      EMBotState.PARTICIPANT_UNREGISTER.code to ButtonBuilder({"Отменить регистрацию полностью"}) {
        OutputData(objectNode { setEventId(event.id!!) })
      }) + listOf(
      "PARTICIPANT_EVENTS" to ButtonBuilder({"\ud83d\udd19 Назад"})
      )
  )
}

class ParticipantUnregisterAction(inputData: InputData): StateAction {
  override val text = TextMessage("Регистрация отменена.")
  override val buttonTransition = ButtonTransition(buttons = listOf(
    EMBotState.PARTICIPANT_SHOW_EVENT.code to ButtonBuilder({"\ud83d\udd19 Назад к событию"})
  ))

  init {
    val eventId = inputData.contextJson.getEventId() ?: throw IllegalArgumentException("Event ID not found in the input data")
    db {
      deleteFrom(EVENTREGISTRATION).where(EVENTREGISTRATION.PARTICIPANT_ID.`in`(
        select(EVENTTEAMREGISTRATIONVIEW.PARTICIPANT_ID)
          .from(EVENTTEAMREGISTRATIONVIEW)
          .where(EVENTTEAMREGISTRATIONVIEW.REGISTRANT_TGUSERID.eq(inputData.user.id.toLong())
            .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(eventId))
          )
      )).execute()
    }
  }

}
fun ParticipantRecord.eventRegistrationCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->
    val section = node[CB_SECTION]?.asInt() ?: return@onCallback
    if (section != CbSection.EVENTS.ordinal) {
      return@onCallback
    }

    val event = node.getEventId()?.let(::getEventRecord)

    when(node.getCommand()) {
      CbEventCommand.LANDING -> {}
      CbEventCommand.LIST -> {}

      CbEventCommand.REGISTER -> event?.let {
        if (node.getTeamMemberId() == null) {
          tg.userSession.reset()
        }
        registerParticipant(node, it, participant, tg)
      }
      CbEventCommand.UNREGISTER -> {}
    }
  }
}

private fun registerParticipant(
  node: ObjectNode,
  event: EventviewRecord,
  registrant: ParticipantRecord,
  tg: ChainBuilder
) {
  RegistrationFlow(createStorageApiProd(tg), createOutputApiProd(tg, node)).process(event, registrant, node)
}

fun getEventRecord(id: Int): EventviewRecord? =
  db {
    selectFrom(EVENTVIEW).where(EVENTVIEW.ID.eq(id)).fetchOne()
  }
fun getAvailableEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    select(EVENT.ID, EVENT.TITLE, EVENT.START, EVENT.PARTICIPANT_LIMIT)
      .from(EVENT.join(EVENTSERIES).on(EVENT.SERIES_ID.eq(EVENTSERIES.ID))
        .join(EVENTSERIESSUBSCRIPTION).on(EVENTSERIESSUBSCRIPTION.SERIES_ID.eq(EVENTSERIES.ID)))
      .where(EVENTSERIESSUBSCRIPTION.PARTICIPANT_ID.eq(participant.id)).andNot(EVENT.IS_DELETED).andNot(EVENT.IS_ARCHIVED)
      .and(EVENT.START.ge(DSL.currentLocalDateTime().minus(DayToSecond.valueOf(Duration.ofDays(7)))))
      .orderBy(EVENT.START.desc())
      .map {
        EventRecord(id = it.component1(), title = it.component2(), start = it.component3(),
          participantLimit = it.component4())
    }.toList()
  }

fun InputData.getOrCreateParticipant() =
  getOrCreateParticipant(this.user.id.toLong(), this.user.username, 1)

fun ObjectNode.getEventId() = this["e"]?.asInt()
fun ObjectNode.setEventId(eventId: Int) = this.put("e", eventId)
private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let { CbEventCommand.entries[it] } ?: CbEventCommand.LIST
internal fun ObjectNode.setCommand(command: CbEventCommand) = this.put("c", command.id)
