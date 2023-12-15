package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.OrganizerRecord
import com.bardsoftware.embot.db.tables.references.*
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.*

enum class OrgManagerCommand {
  LANDING, EVENT_INFO, EVENT_ADD, EVENT_DELETE, EVENT_EDIT, EVENT_ARCHIVE, EVENT_PARTICIPANT_LIST, EVENT_PARTICIPANT_ADD, EVENT_PARTICIPANT_INFO, ORG_SETTINGS,
  EVENT_PARTICIPANT_DELETE;

  val id get() = ordinal + 200
}
fun organizationManagementModule(tg: ChainBuilder) {
  eventDialog(tg, titleMdwn = "Создаём новое событие\\.", OrgManagerCommand.EVENT_ADD) { input ->
    input.apply {
      setDialogId(OrgManagerCommand.EVENT_ADD.id)
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
        put("primary_address", event.primaryAddress)
        if (event.primaryLat != null && event.primaryLon != null) {
          put("primary_latlon", LatLon(event.primaryLat!!, event.primaryLon!!).toString())
        }
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
    step("primary_address", DialogDataType.TEXT, "primary_addr", "Адрес [текст]:")
    step("primary_latlon", DialogDataType.LOCATION, "primary_latlon", "Геолокация:",
      "Введите геолокацию в виде двух десятичных чисел, разделённых запятой. Первое число -- это широта в интервале от -90 до 90. Второе число -- долгота в интервале от -180 до 180.")
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
    } else throw RuntimeException("Найдено ${orgs.size} управляемых вами организаций")
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
        isOrg = true,
        registeredParticipantsMdwn = """
          |$participantNames
          |
          |Итого ${participants.size}""".trimMargin()
      ), TextMarkup.MARKDOWN
    )
  }
  override val buttonTransition: ButtonTransition
    get() = ButtonTransition(listOf(
      "ORG_EVENT_EDIT" to ButtonBuilder({"Редактировать событие..."},
        output = { OutputData(objectNode(baseOutputJson) {
          setSection(CbSection.DIALOG)
          setDialogId(OrgManagerCommand.EVENT_EDIT.id)
        }) }),
      "ORG_EVENT_PARTICIPANT_LIST" to ButtonBuilder({"Участники >>"}, output = { OutputData(baseOutputJson) }),
      "ORG_EVENT_ARCHIVE"          to ButtonBuilder({"Архивировать"}, output = { OutputData(baseOutputJson) }),
      "ORG_EVENT_DELETE"           to ButtonBuilder({"Удалить"},      output = { OutputData(baseOutputJson)}),
      "ORG_LANDING"                to ButtonBuilder({"<< Назад"}, output = {OutputData(objectNode {
        setOrganizationId(event.organizerId!!)
      })})
    ))

}

class OrgEventParticipantListAction(private val participants: List<EventteamregistrationviewRecord>): StateAction {
  constructor(input: InputData): this(
    input.contextJson.getEventId()?.let(::getEventRecord)?.getParticipants() ?: throw RuntimeException("Can't fetch the list of participants from the database")
  )

  override val text = TextMessage("Здесь вы можете добавить или удалить участников события")
  override val buttonTransition = ButtonTransition(
    buttons = participants.map {participant ->
      "ORG_EVENT_PARTICIPANT_INFO" to ButtonBuilder({participant.participantName!!}) {
        OutputData(objectNode {
          setAll<ObjectNode>(it.contextJson)
          setParticipantId(participant.participantId!!)
        })
      }
    } + listOf(
      "ORG_EVENT_PARTICIPANT_ADD" to ButtonBuilder({"Добавить участника >>"}),
      "ORG_EVENT_INFO" to ButtonBuilder({"<< Назад"})
    )
  )

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
    val start = eventData["start"]?.asText()?.toDate()?.getOrElse { null } ?: return@db false
    val seriesId = eventData["series_id"]?.asInt() ?: return@db false
    val limit = eventData["limit"]?.asInt()
    val primaryAddress = eventData["primary_address"]?.asText()
    val primaryLatLon = eventData["primary_latlon"]?.asText()?.toLatLon()?.getOrElse { null }
    eventData["event_id"]?.asInt()?.let {eventId ->
     update(EVENT)
       .set(EVENT.TITLE, title)
       .set(EVENT.START, start)
       .set(EVENT.SERIES_ID, seriesId)
       .set(EVENT.PARTICIPANT_LIMIT, limit)
       .set(EVENT.PRIMARY_ADDRESS, primaryAddress)
       .set(EVENT.PRIMARY_LAT, primaryLatLon?.lat)
       .set(EVENT.PRIMARY_LON, primaryLatLon?.lon)
       .where(EVENT.ID.eq(eventId))
       .execute()
    } ?: run {
      insertInto(EVENT, EVENT.TITLE, EVENT.START, EVENT.SERIES_ID, EVENT.PARTICIPANT_LIMIT, EVENT.PRIMARY_ADDRESS, EVENT.PRIMARY_LAT, EVENT.PRIMARY_LON)
        .values(title, start, seriesId, limit, primaryAddress, primaryLatLon?.lat, primaryLatLon?.lon)
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
  txn {
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

fun unregisterParticipant(eventId: Int, participantId: Int) = runCatching {
  txn {
    println("!!!!!!!! delete eventId=$eventId partici[antId=$participantId")
    deleteFrom(EVENTREGISTRATION).where(EVENTREGISTRATION.PARTICIPANT_ID.eq(participantId).and(EVENTREGISTRATION.EVENT_ID.eq(eventId))).execute()
  }
}
private fun createEscButton() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.MANAGER)
    setCommand(OrgManagerCommand.LANDING)
  }.toString())

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let(OrgManagerCommand.entries::get) ?: OrgManagerCommand.LANDING
fun ObjectNode.setCommand(cmd: OrgManagerCommand) = this.put("c", cmd.ordinal)
private fun ObjectNode.setOrganizationId(organizationId: Int) = this.put("o", organizationId)
private fun ObjectNode.getOrganizationId() = this["o"]?.asInt()

private fun ObjectNode.setParticipantId(id: Int) = this.put("pid", id)
fun ObjectNode.getParticipantId() = this["pid"]?.asInt()