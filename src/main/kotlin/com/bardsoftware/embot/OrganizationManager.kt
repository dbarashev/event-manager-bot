package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok

enum class OrgManagerCommand {
  LANDING, EVENT_INFO, EVENT_ADD, EVENT_DELETE;

  val id get() = ordinal + 200
}
fun ParticipantRecord.organizationManagementCallbacks(tg: ChainBuilder) {
  tg.onCallback {node ->
    if (node.getSection() != CbSection.MANAGER) {
      return@onCallback
    }

    if (node.getDialogId() == null) {
      tg.userSession.reset()
    }
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
          val participants = event.getParticipants()
          val participantNames = participants.joinToString(separator = "\n") {
            """
              ${it.participantName!!.escapeMarkdown()}\, ${it.participantAge}\. 
              Связь\: [${it.registrantUsername}](https://t.me/${it.registrantUsername}), [\+995551172098](tel:+995551172098)
              
              """.trimIndent()
          }.trim().ifBlank { "пока никто не зарегистрировался" }
          tg.reply(event.formatDescription("""
            |$participantNames
            |
            |Итого ${participants.size}""".trimMargin()),
            buttons = listOf(
              BtnData("Удалить", callbackData = json(node) {
                setCommand(OrgManagerCommand.EVENT_DELETE)
              }),
              createEscButton()
            ),
            isMarkdown = true, isInplaceUpdate = true
          )
        }
      }
      OrgManagerCommand.EVENT_ADD -> {
        // This is handled in the dialog code
      }
      OrgManagerCommand.EVENT_DELETE -> {
        node.getEventId()?.let {
          deleteEvent(node)
          tg.reply("Событие перемещено в помойку", buttons = listOf(createEscButton()), isInplaceUpdate = true)
        }
      }
    }
  }
  tg.dialog(
    id = OrgManagerCommand.EVENT_ADD.id,
    intro = """
      Создаём новое событие\. 
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {

    trigger = json {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ADD)
    }
    setup = { input -> jacksonObjectMapper().createObjectNode().apply {
      put("org_id", input.getOrganizationId())
      put("series_id", 1)
    }}
    exitPayload = json {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.LANDING)
    }
    step("title", DialogDataType.TEXT, "Название", "Название события:")
    step("start", DialogDataType.DATE, "Дата", "Дата события [YYYY-MM-DD]:",
      "Введите дату в формате YYYY-MM-DD. Например, 2023-11-21.")
    step("limit", DialogDataType.INT, "Участников (max)", "Максимальное количество участников:")
    confirm("Создаём?") {json ->
      if (createEvent(json)) Ok(json) else Err("Что-то пошло не так")
    }
  }
}

fun EventviewRecord.getParticipants(): List<EventteamregistrationviewRecord> = db {
  selectFrom(EVENTTEAMREGISTRATIONVIEW).where(EVENTTEAMREGISTRATIONVIEW.ID.eq(this@getParticipants.id!!)).toList()
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
    *${organizerTitle.escapeMarkdown()}*
    
    Тут можно управлять событиями\. Можно добавить новое или узнать состояние существующего\. 
    """.trimIndent(),
    buttons = eventAddBtns + eventInfoBtns + escButtons,
    maxCols = 1, isInplaceUpdate = true, isMarkdown = true)
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
    val limit = eventData["limit"]?.asInt()
    insertInto(EVENT, EVENT.TITLE, EVENT.START, EVENT.SERIES_ID, EVENT.PARTICIPANT_LIMIT)
      .values(title, start.atStartOfDay(), seriesId, limit)
      .execute()
    true
  }

fun deleteEvent(eventData: ObjectNode) =
  db {
    update(EVENT).set(EVENT.IS_DELETED, true).where(EVENT.ID.eq(eventData.getEventId())).execute()
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
