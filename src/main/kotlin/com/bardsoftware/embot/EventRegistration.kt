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

fun ParticipantRecord.eventRegistrationCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->
    val section = node[CB_SECTION]?.asInt() ?: return@onCallback
    if (section != CbSection.EVENTS.ordinal) {
      return@onCallback
    }

    tg.userSession.reset()
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
        val registeredTeam = db {
          selectFrom(EVENTTEAMREGISTRATIONVIEW).where(
            EVENTTEAMREGISTRATIONVIEW.REGISTRANT_TGUSERID.eq(participant.userId)
              .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(event.id))
          ).toList()
        }
        val registeredLabel =
          if (registeredTeam.isEmpty()) "из вашей команды пока никто"
          else registeredTeam.map { it.participantName }.joinToString(separator = ", ")
        val btns =
          listOf(
            BtnData("Зарегистрироваться", node.put(CB_COMMAND, CbEventCommand.REGISTER.id).toString())
          ) + if (registeredTeam.isNotEmpty()) { listOf(
            BtnData("Отменить регистрацию полностью", node.put(CB_COMMAND, CbEventCommand.UNREGISTER.id).toString())
          )} else {
            emptyList()
          }
        // -------------------------------------------------------------------------
        tg.reply(event.formatDescription(registeredLabel), isMarkdown = true, buttons = btns + returnToEventRegistrationLanding(),
          isInplaceUpdate = true)
        // -------------------------------------------------------------------------
      }

      CbEventCommand.REGISTER -> {
        node.getParticipantId()?.let(::findParticipant)?.let {otherParticipant ->
          node.clearParticipantId()
          event.register(participant, otherParticipant)
          // -------------------------------------------------------------------------
          tg.reply("Зарегистрировали: ${otherParticipant.displayName}",
            buttons = participant.getEventButtons(node),
            maxCols = 1,
            isInplaceUpdate = true
          )
          // -------------------------------------------------------------------------

          return@onCallback
        }
        participant.teamMembers().let {
          if (it.isEmpty()) {
            event.register(participant, participant)
            // -------------------------------------------------------------------------
            tg.reply("Вы зарегистрированы!", buttons = participant.getEventButtons(node), maxCols = 1, isInplaceUpdate = true)
            // -------------------------------------------------------------------------
          } else {
            // -------------------------------------------------------------------------
            tg.reply("Кого вы будете регистрировать?",
              buttons = listOf(
                BtnData("Себя", node.put("id", participant.id).toString())) + it.map { member ->
                  BtnData("${member.displayName}, ${member.age}", node.put("id", member.id).toString())
                },
              maxCols = 4,
              isInplaceUpdate = true
            )
            // -------------------------------------------------------------------------
          }
        }
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
      }
    }
  }
}

fun getEventRecord(id: Int): EventviewRecord? =
  db {
    selectFrom(EVENTVIEW).where(EVENTVIEW.ID.eq(id)).fetchOne()
  }
fun getAvailableEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    selectFrom(EVENT).toList()
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

fun EventRecord.formatUncheckedLabel() = """${this.title} / ${this.start}"""

fun EventviewRecord.formatDescription(registeredParticipantsMdwn: String) =
  """*${title!!.escapeMarkdown()}*
    |${seriesTitle?.escapeMarkdown() ?: ""}
    | ${"\\-".repeat(20)}
    | *Организаторы*\: ${organizerTitle?.escapeMarkdown() ?: ""}
    | *Дата*\: ${start.toString().escapeMarkdown()}
    | *Max\. участников*\: ${participantLimit?.toString() ?: "\\-"}
    | 
    | *Зарегистрированы*\: 
    | ${registeredParticipantsMdwn}
  """.trimMargin()

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

private fun ObjectNode.getEventId() = this["e"]?.asInt()
private fun ObjectNode.setEventId(eventId: Int) = this.put("e", eventId)
private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let { CbEventCommand.entries[it] } ?: CbEventCommand.LIST
private fun ObjectNode.setCommand(command: CbEventCommand) = this.put("c", command.id)
private fun ObjectNode.getParticipantId() = this["id"]?.asInt()
private fun ObjectNode.clearParticipantId() = this.remove("id")