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
  LANDING, EVENT_INFO, EVENT_ADD, EVENT_DELETE, EVENT_EDIT;

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
              Связь\: [${it.registrantUsername!!.escapeMarkdown()}](https://t.me/${it.registrantUsername}), [${it.registrantPhone!!.escapeMarkdown()}](tel:${it.registrantPhone!!.escapeMarkdown()})
              
              """.trimIndent()
          }.trim().ifBlank { "пока никто не зарегистрировался" }
          tg.reply(event.formatDescription("""
            |$participantNames
            |
            |Итого ${participants.size}""".trimMargin()),
            buttons = listOf(
              BtnData("Редактировать...", callbackData = json(node) {
                setCommand(OrgManagerCommand.EVENT_EDIT)
              }),
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
      OrgManagerCommand.EVENT_EDIT -> {
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
  eventDialog(tg, titleMdwn = "Создаём новое событие\\.", OrgManagerCommand.EVENT_ADD) { input ->
    jacksonObjectMapper().createObjectNode().apply {
      put("org_id", input.getOrganizationId())
      put("series_id", getDefaultSeries(input.getOrganizationId()!!)!!.id)
    }
  }
  eventDialog(tg, titleMdwn = "Редактируем событие\\.", OrgManagerCommand.EVENT_EDIT) { input ->
    input.getEventId()?.let(::getEventRecord)?.let { event ->
      jacksonObjectMapper().createObjectNode().apply {
        put("org_id", input.getOrganizationId())
        put("series_id", getDefaultSeries(input.getOrganizationId()!!)!!.id)
        put("event_id", event.id)
        put("title", event.title)
        put("start", event.start.toString())
        put("limit", event.participantLimit)
      }
    } ?: input
  }
}

fun eventDialog(tg: ChainBuilder, titleMdwn: String, command: OrgManagerCommand, setupCode: (ObjectNode)->ObjectNode) {
  tg.dialog(
    id = command.id,
    intro = """
      $titleMdwn
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {

    trigger = json {
      setSection(CbSection.MANAGER)
      setCommand(command)
    }
    setup = {
      exitPayload = json {
        setSection(CbSection.MANAGER)
        setCommand(OrgManagerCommand.LANDING)
      }
      setupCode(it)
    }
    step("title", DialogDataType.TEXT, "Название", "Название события:")
    step("start", DialogDataType.DATE, "Дата", "Дата события [YYYY-MM-DD HH:mm]:",
      "Введите дату и время начала в формате YYYY-MM-DD HH:mm. Например, 2023-11-21 09:00.")
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
      setOrganizationId(organizerId)
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

fun getDefaultSeries(organizerId: Int) = db {
  selectFrom(EVENTSERIES).where(EVENTSERIES.ORGANIZER_ID.eq(organizerId).and(EVENTSERIES.IS_DEFAULT)).fetchOne()
}

fun createEvent(eventData: ObjectNode): Boolean =
  db {
    val title = eventData["title"]?.asText() ?: return@db false
    val start = eventData["start"]?.asText()?.toDate()?.getOrNull() ?: return@db false
    val seriesId = eventData["series_id"]?.asInt() ?: return@db false
    val limit = eventData["limit"]?.asInt()
    eventData["event_id"]?.asInt()?.let {eventId ->
     update(EVENT)
       .set(EVENT.TITLE, title)
       .set(EVENT.START, start)
       .set(EVENT.SERIES_ID, seriesId)
       .set(EVENT.PARTICIPANT_LIMIT, limit)
       .where(EVENT.ID.eq(eventId))
       .execute()
    } ?: run {
      insertInto(EVENT, EVENT.TITLE, EVENT.START, EVENT.SERIES_ID, EVENT.PARTICIPANT_LIMIT)
        .values(title, start, seriesId, limit)
        .execute()
    }
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
