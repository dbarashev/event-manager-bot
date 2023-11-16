package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.displayName

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
    regularLanding(tg, isInplaceUpdate = false)
  }

}

fun regularLanding(tg: ChainBuilder, isInplaceUpdate: Boolean) {
  val managedOrgs = getManagedOrganizations(tg.userId)
  if (managedOrgs.isEmpty()) {
    userLanding(tg, isInplaceUpdate = isInplaceUpdate)
  } else {
    tg.reply("Привет ${tg.fromUser?.displayName()}!",
      buttons = listOf(
        BtnData("Организатор >>", callbackData = OBJECT_MAPPER.createObjectNode().apply {
          setSection(CbSection.MANAGER)
        }.toString()),
        BtnData("Участник >>", callbackData = OBJECT_MAPPER.createObjectNode().apply {
          setSection(CbSection.PARTICIPANT)
        }.toString())
      )
    )
  }
}

fun userLanding(tg: ChainBuilder, isInplaceUpdate: Boolean) {
  val btnTeam = BtnData("Моя команда", """{"$CB_SECTION": ${CbSection.TEAM.id}}""")
  val btnEvents = BtnData("Мои события", """{"$CB_SECTION": ${CbSection.EVENTS.id}}""")
  tg.reply("Привет ${tg.fromUser?.displayName()}!", buttons = listOf(btnTeam, btnEvents), isInplaceUpdate = isInplaceUpdate)
}

fun returnToFirstLanding() =
  BtnData("<< Назад", callbackData = OBJECT_MAPPER.createObjectNode().apply {
    setSection(CbSection.LANDING)
  }.toString())

