package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.displayName
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

fun ParticipantRecord.landing(tg: ChainBuilder) {
  tg.onCallback {node ->
    if (!setOf(CbSection.LANDING, CbSection.PARTICIPANT).contains(node.getSection())) {
      return@onCallback
    }

    tg.userSession.reset()

    when (node.getSection()) {
      CbSection.PARTICIPANT -> {
        userLanding(tg, isInplaceUpdate = true)
      }
      else -> {
        regularLanding(tg, isInplaceUpdate = true)
      }
    }
  }

  tg.onCommand("start") {
    if (!deepLinkLanding(tg)) {
      regularLanding(tg, isInplaceUpdate = false)
    }
  }
}

fun ParticipantRecord.deepLinkLanding(tg: ChainBuilder) =
  tg.messageText.removePrefix("/start").trim().toIntOrNull()?.let(::getEventRecord)?.let {event ->
    showEvent(this, event, tg, isInplaceUpdate = false)
    true
  } ?: false

fun regularLanding(tg: ChainBuilder, isInplaceUpdate: Boolean) {
  val managedOrgs = getManagedOrganizations(tg.userId)
  if (managedOrgs.isEmpty()) {
    userLanding(tg, isInplaceUpdate = isInplaceUpdate)
  } else {
    tg.reply("Привет ${tg.fromUser?.displayName()}!",
      buttons = listOf(
        BtnData("Организатор >>", callbackData = json {
          setSection(CbSection.MANAGER)
        }),
        BtnData("Участник >>", callbackData = json {
          setSection(CbSection.PARTICIPANT)
        }),
        BtnData("Настройки >>", callbackData = json {
          setSection(CbSection.SETTINGS)
        })
      ),
      isInplaceUpdate = isInplaceUpdate,
      maxCols = 2
    )
  }
}

fun userLanding(tg: ChainBuilder, isInplaceUpdate: Boolean) {
  val btnTeam = BtnData("Моя команда", """{"$CB_SECTION": ${CbSection.TEAM.id}}""")
  val btnEvents = BtnData("Мои события", """{"$CB_SECTION": ${CbSection.EVENTS.id}}""")
  tg.reply("Привет ${tg.fromUser?.displayName()}!", buttons = listOf(btnTeam, btnEvents, returnToFirstLanding()),
    isInplaceUpdate = isInplaceUpdate)
}

fun returnToFirstLanding() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.LANDING)
  }.toString())

fun returnToParticipantLanding() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.PARTICIPANT)
  }.toString())
