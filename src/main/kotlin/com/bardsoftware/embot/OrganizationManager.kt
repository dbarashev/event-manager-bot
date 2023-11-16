package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.EVENTTEAMREGISTRATIONVIEW
import com.bardsoftware.embot.db.tables.references.EVENTVIEW
import com.bardsoftware.embot.db.tables.references.ORGANIZER
import com.bardsoftware.embot.db.tables.references.ORGANIZERMANAGER
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode

enum class OrgManagerCommand {
  LANDING, EVENT_INFO
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
              BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
                setSection(CbSection.MANAGER)
                setCommand(OrgManagerCommand.LANDING)
              }.toString())
            ),
            isMarkdown = true, isInplaceUpdate = true
          )
        }
      }
    }
  }
}

fun EventviewRecord.getParticipants() = db {
  selectFrom(EVENTTEAMREGISTRATIONVIEW).where(EVENTTEAMREGISTRATIONVIEW.ID.eq(this@getParticipants.id!!))
}
fun ParticipantRecord.eventOrganizerLanding(tg: ChainBuilder, organizerId: Int, organizerTitle: String) {
  val btns = getAllEvents(organizerId).map { eventRecord ->
    BtnData(eventRecord.title!!, OBJECT_MAPPER.createObjectNode().apply {
      setSection(CbSection.MANAGER)
      setCommand(OrgManagerCommand.EVENT_INFO)
      setEventId(eventRecord.id!!)
    }.toString())
  }.toList()
  tg.reply("""
    *Организация:* $organizerTitle
    
    Выберите событие
    """.trimIndent(), buttons = btns, isInplaceUpdate = true)
}


fun getManagedOrganizations(tgUserId: Long) = db {
  selectFrom(ORGANIZERMANAGER.join(ORGANIZER).on(ORGANIZER.ID.eq(ORGANIZERMANAGER.ORGANIZER_ID)))
    .where(ORGANIZERMANAGER.USER_ID.eq(tgUserId)).toList()
}

fun getAllEvents(organizerId: Int) = db {
  selectFrom(EVENTVIEW).where(EVENTVIEW.ORGANIZER_ID.eq(organizerId)).toList()
}

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let(OrgManagerCommand.entries::get) ?: OrgManagerCommand.LANDING
private fun ObjectNode.setCommand(cmd: OrgManagerCommand) = this.put("c", cmd.ordinal)
private fun ObjectNode.setEventId(eventId: Int) = this.put("e", eventId)
private fun ObjectNode.getEventId() = this["e"]?.asInt()