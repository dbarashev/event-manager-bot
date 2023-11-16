package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Result
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class OrgManagerCommand {
  LANDING, EVENT_INFO, EVENT_ADD;

  val id get() = ordinal + 200
}
fun ParticipantRecord.organizationManagementCallbacks(tg: ChainBuilder) {
  tg.onCallback {node ->
    if (node.getSection() != CbSection.MANAGER) {
      return@onCallback
    }

    tg.userSession.reset()
    when (node.getCommand()) {
      OrgManagerCommand.LANDING -> {
        val orgs = getManagedOrganizations(this.userId!!)
        when (orgs.size) {
          0 -> {
            tg.reply("У вас пока что нет организаторских прав")
          }
          1 -> {
            this.eventOrganizerLanding(tg, orgs[0].get(ORGANIZER.ID)!!, orgs[0].get(ORGANIZER.TITLE)!!)
          }
          else -> {

          }
        }
      }
      OrgManagerCommand.EVENT_INFO -> {
        node.getEventId()?.let(::getEventRecord)?.let { event ->
          val participants = event.getParticipants().joinToString(separator = "\n ") {
            it.participantName!!
          }.trim().ifBlank { "пока никто не зарегистрировался" }
          tg.reply(event.formatDescription(participants),
            buttons = listOf(
              createEscButton()
            ),
            isMarkdown = true, isInplaceUpdate = true
          )
        }
      }

      OrgManagerCommand.EVENT_ADD -> {
        tg.userSession.save(OrgManagerCommand.EVENT_ADD.id, OBJECT_MAPPER.createObjectNode().apply {
          this.put("org_id", node.getOrganizationId())
          this.put("series_id", 1)
          this.put("next_field", "title")
          tg.reply("Введите название:")
        }.toString())
      }
    }
  }
  tg.onInput(OrgManagerCommand.EVENT_ADD.id) {input ->
    val eventData = tg.userSession.state?.asJson() ?: OBJECT_MAPPER.createObjectNode()
    val nextField = eventData["next_field"]?.asText() ?: "title"
    when (nextField){
      "title" -> {
        eventData.put("title", input)
        eventData.put("next_field", "start")
        tg.userSession.save(OrgManagerCommand.EVENT_ADD.id, eventData.toString())
        tg.reply("Введите дату [YYYY-MM-DD]:")
      }
      "start" -> {
        input.toDate().getOrElse {
          tg.reply("Дата должна быть в формате YYYY-DD-MM")
          return@onInput
        }
        eventData.put("start", input)
        if (createEvent(eventData)) {
          tg.userSession.reset()
          tg.reply("Событие создано", buttons = listOf(createEscButton()))
        } else {
          tg.userSession.reset()
          tg.reply("Что-то пошло не так", buttons = listOf(createEscButton()))
        }
      }
    }
  }

}

fun EventviewRecord.getParticipants() = db {
  selectFrom(EVENTTEAMREGISTRATIONVIEW).where(EVENTTEAMREGISTRATIONVIEW.ID.eq(this@getParticipants.id!!))
}
fun ParticipantRecord.eventOrganizerLanding(tg: ChainBuilder, organizerId: Int, organizerTitle: String) {
  val eventAddBtns = listOf(
    BtnData("Добавить событие", callbackData = OBJECT_MAPPER.createObjectNode().apply {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ADD)
      setOrganizationId(organizerId)
    }.toString())
  )
  val eventInfoBtns = getAllEvents(organizerId).map { eventRecord ->
    BtnData(eventRecord.title!!, OBJECT_MAPPER.createObjectNode().apply {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
      setEventId(eventRecord.id!!)
    }.toString())
  }.toList()
  val escButtons = listOf(returnToFirstLanding())
  tg.reply("""
    *Организация:* $organizerTitle
    
    Выберите событие
    """.trimIndent(), buttons = eventAddBtns + eventInfoBtns + escButtons, maxCols = 1, isInplaceUpdate = true)
}


fun getManagedOrganizations(tgUserId: Long) = db {
  selectFrom(ORGANIZERMANAGER.join(ORGANIZER).on(ORGANIZER.ID.eq(ORGANIZERMANAGER.ORGANIZER_ID)))
    .where(ORGANIZERMANAGER.USER_ID.eq(tgUserId)).toList()
}

fun getAllEvents(organizerId: Int) = db {
  selectFrom(EVENTVIEW).where(EVENTVIEW.ORGANIZER_ID.eq(organizerId)).toList()
}

fun createEvent(eventData: ObjectNode): Boolean =
  db {
    val title = eventData["title"]?.asText() ?: return@db false
    val start = eventData["start"]?.asText()?.toDate()?.getOrNull() ?: return@db false
    val seriesId = eventData["series_id"]?.asInt() ?: return@db false
    insertInto(EVENT, EVENT.TITLE, EVENT.START, EVENT.SERIES_ID).values(title, start.atStartOfDay(), seriesId).execute()
    true
  }

private fun createEscButton() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.MANAGER)
    setCommand(OrgManagerCommand.LANDING)
  }.toString())

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let(OrgManagerCommand.entries::get) ?: OrgManagerCommand.LANDING
private fun ObjectNode.setCommand(cmd: OrgManagerCommand) = this.put("c", cmd.ordinal)
private fun ObjectNode.setEventId(eventId: Int) = this.put("e", eventId)
private fun ObjectNode.getEventId() = this["e"]?.asInt()
private fun ObjectNode.setOrganizationId(organizationId: Int) = this.put("o", organizationId)
private fun ObjectNode.getOrganizationId() = this["o"]?.asInt()

private fun String.toDate() =
  Result.runCatching { LocalDate.parse(this@toDate, DateTimeFormatter.ISO_DATE) }