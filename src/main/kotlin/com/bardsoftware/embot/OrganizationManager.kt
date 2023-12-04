package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.OrganizerRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.*

enum class OrgManagerCommand {
  LANDING, EVENT_INFO, EVENT_ADD, EVENT_DELETE, EVENT_EDIT, EVENT_ARCHIVE, ORG_SETTINGS;

  val id get() = ordinal + 200
}
fun organizationManagementModule(tg: ChainBuilder) {
  eventDialog(tg, titleMdwn = "Создаём новое событие\\.", OrgManagerCommand.EVENT_ADD) { input ->
    input.apply {
      setDialogId(OrgManagerCommand.EVENT_EDIT.id)
      put("org_id", input.getOrganizationId())
      put("series_id", getDefaultSeries(input.getOrganizationId()!!)!!.id)
    }
  }
  eventDialog(tg, titleMdwn = "Редактируем событие\\.", OrgManagerCommand.EVENT_EDIT) { input ->
    input.getEventId()?.let(::getEventRecord)?.let { event ->
      input.apply {
        setDialogId(OrgManagerCommand.EVENT_EDIT.id)
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
    confirm("Создаём/обновляем?") {json ->
      if (createEvent(json)) {
        tg.reply("Готово", buttons = listOf(escapeButton), isInplaceUpdate = true)
        tg.userSession.reset()
        Ok(json)
      } else Err("Что-то пошло не так")
    }
  }
}

fun EventviewRecord.getParticipants(): List<EventteamregistrationviewRecord> = db {
  selectFrom(EVENTTEAMREGISTRATIONVIEW).where(EVENTTEAMREGISTRATIONVIEW.ID.eq(this@getParticipants.id!!)).toList()
}

fun archiveEvent(eventId: Int) = txn {
  update(EVENT).set(EVENT.IS_ARCHIVED, true).where(EVENT.ID.eq(eventId)).execute()
}

class OrgLandingAction(input: InputData): StateAction {
  private val orgRecord: OrganizerRecord
  init {
    val orgs = getManagedOrganizations(input.user.id.toLong())
    if (orgs.size == 1) {
      orgRecord = OrganizerRecord().apply {
        id = orgs[0].get(ORGANIZER.ID)
        title = orgs[0].get(ORGANIZER.TITLE)
        googleSheetId = orgs[0].get(ORGANIZER.GOOGLE_SHEET_ID)
      }
    } else throw RuntimeException("Найдено ${orgs.size} управляемых организаций")
  }

  override val text get() = TextMessage(
    """
    *${orgRecord.title!!.escapeMarkdown()}*
    
    \-\-\-\-
    Связанная Google\-таблица\: ${orgRecord.googleSheetId?.let { "✔" } ?: "нет"}  
    \-\-\-\-
    Используйте кнопки внизу для управления событиями или изменения настроек\.
    """.trimIndent(), TextMarkup.MARKDOWN)

  override val buttonTransition get() = ButtonTransition(listOf(
    "ORG_EVENT_ADD" to ButtonBuilder({ "Создать событие..." }) {
      OutputData(objectNode { setOrganizationId(orgRecord.id!!) })
    }
  ) + getAllEvents(orgRecord.id!!).map {eventRecord ->
    "ORG_EVENT_INFO" to ButtonBuilder({eventRecord.title!!}) {
      OutputData(objectNode {
        setOrganizationId(orgRecord.id!!)
        setEventId(eventRecord.id!!)
      })
    }
  } + listOf(
    "ORG_SETTINGS" to ButtonBuilder({"Настройки..."}) {
      OutputData(objectNode {
        setSection(CbSection.DIALOG)
        setDialogId(OrgManagerCommand.ORG_SETTINGS.id)
        setOrganizationId(orgRecord.id!!)
      })
    }
  ) + listOf(
    "START" to ButtonBuilder({"<< Назад"})
  ))
}

class OrgEventInfoAction(private val input: InputData): StateAction {
  private val event: EventviewRecord
  private val participants: List<EventteamregistrationviewRecord>
  private val baseOutputJson: ObjectNode
  init {
    event = input.contextJson.getEventId()?.let(::getEventRecord) ?: throw RuntimeException("")
    participants = event.getParticipants()
    baseOutputJson = objectNode(input.contextJson) {
      setEventId(event.id!!)
      setOrganizationId(event.organizerId!!)
    }
  }
  override val text: TextMessage get() {
    val participantNames = participants.joinToString(separator = "\n") {
      """
      ${it.participantName!!.escapeMarkdown()}\, ${it.participantAge}\. 
      Связь\: [${it.registrantUsername!!.escapeMarkdown()}](https://t.me/${it.registrantUsername}), [${it.registrantPhone!!.escapeMarkdown()}](tel:${it.registrantPhone!!.escapeMarkdown()})
      
      """.trimIndent()
    }.trim().ifBlank { "пока никто не зарегистрировался" }
    return TextMessage(
      event.formatDescription(
        """
      |$participantNames
      |
      |Итого ${participants.size}""".trimMargin()
      ), TextMarkup.MARKDOWN
    )
  }
  override val buttonTransition: ButtonTransition
    get() = ButtonTransition(listOf(
      "ORG_EVENT_EDIT" to ButtonBuilder({"Редактировать..."},
        output = { OutputData(objectNode(baseOutputJson) {
          setSection(CbSection.DIALOG)
          setDialogId(OrgManagerCommand.EVENT_EDIT.id)
        }) }),
      "ORG_EVENT_ARCHIVE" to ButtonBuilder({"Архивировать"}, output = { OutputData(baseOutputJson) }),
      "ORG_EVENT_DELETE"  to ButtonBuilder({"Удалить"},      output = { OutputData(baseOutputJson)}),
      "ORG_LANDING"       to ButtonBuilder({"<< Назад"}, output = {OutputData(objectNode {
        setOrganizationId(event.organizerId!!)
      })})
    ))

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

fun deleteEvent(eventId: Int) =
  txn {
    update(EVENT).set(EVENT.IS_DELETED, true).where(EVENT.ID.eq(eventId)).execute()
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
fun ObjectNode.setCommand(cmd: OrgManagerCommand) = this.put("c", cmd.ordinal)
private fun ObjectNode.setOrganizationId(organizationId: Int) = this.put("o", organizationId)
private fun ObjectNode.getOrganizationId() = this["o"]?.asInt()
