package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.EVENT
import com.bardsoftware.embot.db.tables.references.EVENTREGISTRATION
import com.bardsoftware.embot.db.tables.references.EVENTTEAMREGISTRATIONVIEW
import com.bardsoftware.embot.db.tables.references.EVENTVIEW
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.escapeMarkdown
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

    val command = node["c"]?.asInt()?.let { CbEventCommand.entries[it] } ?: CbEventCommand.LIST
    when(command) {
      CbEventCommand.LIST -> {
        val registeredTeam = db {
          selectFrom(EVENTTEAMREGISTRATIONVIEW).where(
            EVENTTEAMREGISTRATIONVIEW.LEADER_ID.eq(participant.id)
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
        tg.reply(
          """*${event.title!!.escapeMarkdown()}*
              |${event.seriesTitle?.escapeMarkdown() ?: ""}
              | ${"\\-".repeat(20)}
              | *Организаторы*\: ${event.organizerTitle?.escapeMarkdown() ?: ""}
              | *Дата*\: ${event.start.toString().escapeMarkdown()}
              | *Зарегистрированы*\: ${registeredLabel.escapeMarkdown()}
            """.trimMargin(), isMarkdown = true, buttons = btns)
      }

      CbEventCommand.REGISTER -> {
        node["id"]?.asInt()?.let(::findParticipant)?.let {otherParticipant ->
          event.register(otherParticipant)
          tg.reply("Зарегистрировали: ${otherParticipant.displayName}")
          return@onCallback
        }
        participant.teamMembers().let {
          if (it.isEmpty()) {
            event.register(participant)
            tg.reply("Вы зарегистрированы!", buttons = participant.getEventButtons(node), maxCols = 1)
          } else {
            tg.reply("Кого вы будете регистрировать?", buttons =
              listOf(BtnData("Себя", node.put("id", participant.id).toString())) +
              it.map { member ->
                BtnData("${member.displayName}, ${member.age}", node.put("id", member.id).toString())
              }
            )
          }
        }
      }
      CbEventCommand.UNREGISTER -> {
        db {
          deleteFrom(EVENTREGISTRATION).where(EVENTREGISTRATION.PARTICIPANT_ID.`in`(
            select(EVENTTEAMREGISTRATIONVIEW.PARTICIPANT_ID)
              .from(EVENTTEAMREGISTRATIONVIEW)
              .where(EVENTTEAMREGISTRATIONVIEW.LEADER_ID.eq(participant.id)
                .and(EVENTTEAMREGISTRATIONVIEW.ID.eq(event.id))
              )
          )).execute()
        }
        tg.reply("Регистрация отменена", buttons = participant.getEventButtons(node), maxCols = 1)
      }
    }
  }
}

fun getEvent(id: Int): EventviewRecord? =
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
      srcNode.put(CB_EVENT, it.id)
        .put(CB_COMMAND, CbEventCommand.LIST.id)
        .toString()
    )

  }.toList()

fun EventRecord.formatUncheckedLabel() = """${this.title} / ${this.start}"""

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