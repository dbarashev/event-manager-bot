package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jooq.impl.DSL
import org.jooq.types.DayToSecond
import java.time.Duration

fun ParticipantRecord.eventRegistrationCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->
    val section = node[CB_SECTION]?.asInt() ?: return@onCallback
    if (section != CbSection.EVENTS.ordinal) {
      return@onCallback
    }

    val event = node.getEventId()?.let(::getEventRecord) ?: run {
      // -------------------------------------------------------------------------
      tg.reply("Ваши события:",
        buttons = participant.getEventButtons(node) + returnToParticipantLanding(),
        maxCols = 1, isInplaceUpdate = true)
      // -------------------------------------------------------------------------
      return@onCallback
    }

    when(node.getCommand()) {
      CbEventCommand.LIST -> {
        showEvent(participant, event, tg)
      }

      CbEventCommand.REGISTER -> {
        registerParticipant(node, event, participant, tg)
      }
      CbEventCommand.UNREGISTER -> {
        db {
          deleteFrom(EVENTREGISTRATION).where(EVENTREGISTRATION.PARTICIPANT_ID.`in`(
            select(EVENTTEAMREGISTRATIONVIEW.PARTICIPANT_ID)
              .from(EVENTTEAMREGISTRATIONVIEW)
              .where(EVENTTEAMREGISTRATIONVIEW.REGISTRANT_TGUSERID.eq(participant.userId)
                .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(event.id))
              )
          )).execute()
        }
        // -------------------------------------------------------------------------
        tg.reply("Регистрация отменена", buttons = participant.getEventButtons(node), maxCols = 1, isInplaceUpdate = true)
        // -------------------------------------------------------------------------
        tg.userSession.reset()
      }
    }
  }
}

fun showEvent(
  participant: ParticipantRecord,
  event: EventviewRecord,
  tg: ChainBuilder,
  isInplaceUpdate: Boolean = true
) {
  val registeredTeam = db {
    selectFrom(EVENTTEAMREGISTRATIONVIEW).where(
      EVENTTEAMREGISTRATIONVIEW.REGISTRANT_TGUSERID.eq(participant.userId)
        .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(event.id))
    ).toList()
  }
  val registeredLabel =
    if (registeredTeam.isEmpty()) "из вашей команды пока никто"
    else registeredTeam.map { it.participantName!!.escapeMarkdown() }.joinToString(separator = ", ")
  val btns =
    listOf(
      BtnData("Регистрация", json {
        setSection(CbSection.EVENTS)
        setCommand(CbEventCommand.REGISTER)
        setEventId(event.id!!)
      })
    ) + if (registeredTeam.isNotEmpty()) {
      listOf(
        BtnData("Отменить регистрацию полностью", json {
          setSection(CbSection.EVENTS)
          setCommand(CbEventCommand.UNREGISTER)
          setEventId(event.id!!)
        })
      )
    } else {
      emptyList()
    }
  // -------------------------------------------------------------------------
  tg.reply(
    event.formatDescription(registeredLabel),
    buttons = btns + returnToEventRegistrationLanding(),
    isMarkdown = true,
    isInplaceUpdate = isInplaceUpdate
  )
  // -------------------------------------------------------------------------
  tg.userSession.reset()
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

fun ParticipantRecord.getEventButtons(srcNode: ObjectNode) =
  getAvailableEvents(this).map {
    BtnData(it.formatUncheckedLabel(),
      callbackData = srcNode.apply {
        setEventId(it.id!!)
        setCommand(CbEventCommand.LIST)
      }.toString()
    )
  }.toList()

fun EventRecord.formatUncheckedLabel() = """${this.title} / ${this.start!!.toLocalDate()} ${this.start!!.toLocalTime()}"""

fun EventviewRecord.formatDescription(registeredParticipantsMdwn: String) =
  """*${title!!.escapeMarkdown()}*
    |${seriesTitle?.escapeMarkdown() ?: ""}
    | ${"\\-".repeat(40)}
    | *Организаторы*\: ${organizerTitle?.escapeMarkdown() ?: ""}
    | *Дата*\: ${start!!.toLocalDate().toString().escapeMarkdown()}
    | *Время*\: ${start!!.toLocalTime().toString().escapeMarkdown()}
    | *Max\. участников*\: ${participantLimit?.toString() ?: "\\-"}
    | ${"\\-".repeat(40)}
    | 
    | *Зарегистрированы*\: 
    | ${registeredParticipantsMdwn}
    | ${"\\-".repeat(40)}
    | *Ссылка для регистрации*\: [${this.registrationLink().escapeMarkdown()}]
  """.trimMargin()

fun EventviewRecord.registrationLink() = """https://t.me/${System.getenv("TG_BOT_USERNAME")}?start=${this.id}"""
fun EventviewRecord.register(registrant: ParticipantRecord, participant: ParticipantRecord) = txn {
  val subscriptionId = selectFrom(EVENTSERIESSUBSCRIPTION)
    .where(EVENTSERIESSUBSCRIPTION.SERIES_ID.eq(this@register.seriesId)
      .and(EVENTSERIESSUBSCRIPTION.PARTICIPANT_ID.eq(registrant.id))
    ).fetchOne()?.id ?: return@txn
  insertInto(EVENTREGISTRATION)
    .columns(EVENTREGISTRATION.PARTICIPANT_ID, EVENTREGISTRATION.EVENT_ID, EVENTREGISTRATION.SUBSCRIPTION_ID)
    .values(participant.id, this@register.id, subscriptionId).execute()
}

fun EventviewRecord.unregister(participant: ParticipantRecord) = db {
  deleteFrom(EVENTREGISTRATION).where(
    EVENTREGISTRATION.EVENT_ID.eq(this@unregister.id)
      .and(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id))
  ).execute()
}

fun returnToEventRegistrationLanding() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.EVENTS)
  }.toString())

fun ObjectNode.getEventId() = this["e"]?.asInt()
fun ObjectNode.setEventId(eventId: Int) = this.put("e", eventId)
private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let { CbEventCommand.entries[it] } ?: CbEventCommand.LIST
internal fun ObjectNode.setCommand(command: CbEventCommand) = this.put("c", command.id)
