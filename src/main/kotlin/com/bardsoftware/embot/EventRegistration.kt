package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.EVENT
import com.bardsoftware.embot.db.tables.references.EVENTREGISTRATION
import com.bardsoftware.embot.db.tables.references.EVENTVIEW
import com.fasterxml.jackson.databind.node.ObjectNode

fun ParticipantRecord.eventRegistrationCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->
    val section = node[CB_SECTION]?.asInt() ?: return@onCallback
    if (section != CbSection.EVENTS.ordinal) {
      return@onCallback
    }

    val event = node["e"]?.asInt()?.let { getEvent(it) } ?: run {
      tg.reply("Ваши события:", buttons = participant.getEventButtons(node), maxCols = 1)
      return@onCallback
    }

    val command = node["c"]?.asInt() ?: return@onCallback
    when(command) {
      0 -> {
        val btns = if (event.id in getAvailableEvents(participant).map { it.id }) {
          listOf(BtnData("Зарегистрироваться",
            node.put(CB_COMMAND, CbEventCommand.REGISTER.id).toString()
          ))
        } else listOf(BtnData("Отменить регистрацию",
          node.put(CB_COMMAND, CbEventCommand.UNREGISTER.id).toString())
        )
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
        tg.reply("Вы зарегистрированы!", buttons = participant.getEventButtons(node), maxCols = 1)
      }
      2 -> {
        event.unregister(participant)
        tg.reply("Регистрация отменена", buttons = participant.getEventButtons(node), maxCols = 1)
      }
    }
  }
}

fun getEvent(id: Int): EventviewRecord? =
  db {
    selectFrom(EVENTVIEW).where(EVENTVIEW.ID.eq(id)).fetchOne()
  }
fun getRegisteredEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    selectFrom(EVENT).where(
      EVENT.ID.`in`(
      select(EVENTREGISTRATION.EVENT_ID)
        .from(EVENTREGISTRATION)
        .where(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id!!)))
    ).toList()
  }
fun getAvailableEvents(participant: ParticipantRecord): List<EventRecord> =
  db {
    selectFrom(EVENT).where(
      EVENT.ID.notIn(
      select(EVENTREGISTRATION.EVENT_ID)
        .from(EVENTREGISTRATION)
        .where(EVENTREGISTRATION.PARTICIPANT_ID.eq(participant.id!!)))
    ).toList()
  }

fun ParticipantRecord.getEventButtons(srcNode: ObjectNode) =
  (getRegisteredEvents(this).map {
    BtnData(it.formatCheckedLabel(),
      srcNode.put(CB_EVENT, it.id)
        .put(CB_COMMAND, CbEventCommand.LIST.id)
        .toString()
    )
  } + getAvailableEvents(this).map {
    BtnData(it.formatUncheckedLabel(),
      srcNode.put(CB_EVENT, it.id)
        .put(CB_COMMAND, CbEventCommand.LIST.id)
        .toString()
    )

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