package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.OrganizerRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.*

enum class OrgManagerCommand {
  LANDING, EVENT_INFO, EVENT_ADD, EVENT_DELETE, EVENT_EDIT, EVENT_ARCHIVE, ORG_SETTINGS;

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
        eventOrganizerLanding(tg).onFailure { tg.reply(it) }
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
              BtnData("Архивировать", callbackData = json(node) {
                setCommand(OrgManagerCommand.EVENT_ARCHIVE)
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
      OrgManagerCommand.EVENT_ARCHIVE -> {
        node.getEventId()?.let {
          archiveEvent(it)
          eventOrganizerLanding(tg)
        }
      }
      OrgManagerCommand.ORG_SETTINGS -> {
        // This is handled in the dialog code
      }
    }
  }
  eventDialog(tg, titleMdwn = "Создаём новое событие\\.", OrgManagerCommand.EVENT_ADD) { input ->
    input.apply {
      put("org_id", input.getOrganizationId())
      put("series_id", getDefaultSeries(input.getOrganizationId()!!)!!.id)
    }
  }
  eventDialog(tg, titleMdwn = "Редактируем событие\\.", OrgManagerCommand.EVENT_EDIT) { input ->
    input.getEventId()?.let(::getEventRecord)?.let { event ->
      input.apply {
        put("org_id", input.getOrganizationId())
        put("series_id", getDefaultSeries(input.getOrganizationId()!!)!!.id)
        put("event_id", event.id)
        put("title", event.title)
        put("start", event.start.toString())
        put("limit", event.participantLimit)
      }
    } ?: input
  }
  orgSettingsDialog(tg)
}

fun orgSettingsDialog(tg: ChainBuilder) {
  tg.dialog(
    id = OrgManagerCommand.ORG_SETTINGS.id,
    intro = """
      Настройки организации.
    """.trimIndent()) {

    trigger = json {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.ORG_SETTINGS)
    }
    setup = {
      exitPayload = json {
        setSection(CbSection.MANAGER)
        setCommand(OrgManagerCommand.LANDING)
      }
      val org: OrganizerRecord = it.getOrganizationId()?.let(::getOrg)!!
      it.put(ORGANIZER.ID.name, org.id)
      it.put(ORGANIZER.TITLE.name, org.title)
      it.put(ORGANIZER.GOOGLE_SHEET_ID.name, org.googleSheetId)
      it
    }
    step(ORGANIZER.TITLE.name, DialogDataType.TEXT, "Название", "Название вашей организации")
    step(ORGANIZER.GOOGLE_SHEET_ID.name, DialogDataType.TEXT, "Google Sheet ID", "Идентификатор связанной Google таблицы. Прочитайте справку о том, как и зачем привязывать таблицу")
    confirm("Применить?") { json->
      updateOrg(json).andThen {
        tg.userSession.reset()
        tg.reply("Готово", buttons = listOf(escapeButton), isInplaceUpdate = true)
        Ok(Unit)
      }.mapError { " Что-то пошло не так" }
    }
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

fun archiveEvent(eventId: Int) = txn {
  update(EVENT).set(EVENT.IS_ARCHIVED, true).where(EVENT.ID.eq(eventId)).execute()
}

fun ParticipantRecord.eventOrganizerLanding(tg: ChainBuilder): Result<Unit, String> {
  val orgs = getManagedOrganizations(this.userId!!)
  return when (orgs.size) {
    0 -> Err("У вас пока что нет организаторских прав")
    1 -> {
      this.eventOrganizerLanding(tg, OrganizerRecord().apply {
        id = orgs[0].get(ORGANIZER.ID)
        title = orgs[0].get(ORGANIZER.TITLE)
        googleSheetId = orgs[0].get(ORGANIZER.GOOGLE_SHEET_ID)
      })
      Ok(Unit)
    }
    else -> Err("Кажется, у вас более одной организации. Пока что я не умею с этим работать (")
  }
}
fun ParticipantRecord.eventOrganizerLanding(tg: ChainBuilder, org: OrganizerRecord) {
  val eventAddBtns = listOf(
    BtnData("Добавить событие...", callbackData = OBJECT_MAPPER.createObjectNode().apply {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_ADD)
      setOrganizationId(org.id!!)
    }.toString())
  )
  val eventInfoBtns = getAllEvents(org.id!!).map { eventRecord ->
    BtnData(eventRecord.title!!, OBJECT_MAPPER.createObjectNode().apply {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
      setOrganizationId(org.id!!)
      setEventId(eventRecord.id!!)
    }.toString())
  }.toList()
  val escButtons = listOf(
    BtnData("Настройки...", callbackData = json {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.ORG_SETTINGS)
      setOrganizationId(org.id!!)
    }),
    returnToFirstLanding()
  )
  tg.reply("""
    *${org.title!!.escapeMarkdown()}*
    
    \-\-\-\-
    Связанная Google\-таблица\: ${org.googleSheetId?.let { "✔" } ?: "нет"}  
    \-\-\-\-
    Используйте кнопки внизу для управления событиями или изменения настроек\.
    """.trimIndent(),
    buttons = eventAddBtns + eventInfoBtns + escButtons,
    maxCols = 1, isInplaceUpdate = true, isMarkdown = true)
}


fun getManagedOrganizations(tgUserId: Long) = db {
  selectFrom(ORGANIZERMANAGER.join(ORGANIZER).on(ORGANIZER.ID.eq(ORGANIZERMANAGER.ORGANIZER_ID)))
    .where(ORGANIZERMANAGER.USER_ID.eq(tgUserId)).toList()
}

fun getAllEvents(organizerId: Int) = db {
  selectFrom(EVENTVIEW)
    .where(EVENTVIEW.ORGANIZER_ID.eq(organizerId)).andNot(EVENTVIEW.IS_ARCHIVED).andNot(EVENTVIEW.IS_DELETED)
    .orderBy(EVENTVIEW.START.desc())
    .toList()
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

fun getOrg(orgId: Int) = db {
  selectFrom(ORGANIZER).where(ORGANIZER.ID.eq(orgId)).fetchOne()
}

fun updateOrg(json: ObjectNode) = runCatching {
  db {
    val updatedRows = update(ORGANIZER)
      .set(ORGANIZER.TITLE, json[ORGANIZER.TITLE.name]?.asText())
      .set(ORGANIZER.GOOGLE_SHEET_ID, json[ORGANIZER.GOOGLE_SHEET_ID.name]?.asText())
      .where(ORGANIZER.ID.eq(json[ORGANIZER.ID.name]!!.asInt()))
      .execute()
    if (updatedRows != 1) {
      throw RuntimeException("Что-то пошло не так, изменения не внесены")
    }
  }
}.mapError { it.message ?: "Что-то пошло не так" }

private fun createEscButton() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.MANAGER)
    setCommand(OrgManagerCommand.LANDING)
  }.toString())

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let(OrgManagerCommand.entries::get) ?: OrgManagerCommand.LANDING
private fun ObjectNode.setCommand(cmd: OrgManagerCommand) = this.put("c", cmd.ordinal)
private fun ObjectNode.setOrganizationId(organizationId: Int) = this.put("o", organizationId)
private fun ObjectNode.getOrganizationId() = this["o"]?.asInt()
