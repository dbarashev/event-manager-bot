package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAM
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAMVIEW
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode

enum class CbTeamCommand {
  LANDING, ADD_DIALOG, CREATE, RESET;
  val id get() = this.ordinal
}

enum class DlgTeam {
  INPUT_NAME, INPUT_AGE, CREATE;
  val id get() = this.ordinal
}
fun ParticipantRecord.teamManagementCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->

    if (node.getSection() != CbSection.TEAM) {
      return@onCallback
    }

    when (node.getCommand()) {
      CbTeamCommand.LANDING -> {
        landing(tg, node = node, isInplaceUpdate = true)
        tg.userSession.reset()
      }
      CbTeamCommand.ADD_DIALOG -> {
        tg.userSession.save(DlgTeam.INPUT_NAME.id, "")
        tg.reply("Как зовут товарища?")
      }
      CbTeamCommand.RESET -> {
        tg.userSession.reset()
      }
      CbTeamCommand.CREATE -> {
        tg.userSession.state?.asJson()?.let { stateJson ->
          val newDisplayName = stateJson["name"]?.asText() ?: run {
            tg.reply("Не указано имя")
            return@onCallback
          }
          val newAge = stateJson["age"]?.asInt() ?: run {
            tg.reply("Не указан возраст")
            return@onCallback
          }
          db {
            val newParticipant = insertInto(PARTICIPANT).columns(PARTICIPANT.DISPLAY_NAME, PARTICIPANT.AGE)
              .values(newDisplayName, newAge).returning().fetchOne()
            insertInto(PARTICIPANTTEAM).columns(PARTICIPANTTEAM.LEADER_ID, PARTICIPANTTEAM.FOLLOWER_ID)
              .values(participant.id, newParticipant!!.id).execute()
          }
          tg.userSession.reset()
          tg.reply("Товарищ записан в вашу команду. Теперь вы можете регистрировать его во всех доступных вам событиях")
          landing(tg, isInplaceUpdate = false, node = node)
        }
      }
    }
  }
}

fun ParticipantRecord.teamMemberDialog(tg: ChainBuilder) {
  tg.onInput(DlgTeam.INPUT_NAME.id) {msg ->
    db {
      tg.userSession.save(DlgTeam.INPUT_AGE.id,
        OBJECT_MAPPER.createObjectNode().put("name", msg.trim()).toString()
      )
      // ----------------------------------------------------------------------------------
      tg.reply("Сколько ему лет? [введите число]", stop = true)
      // ----------------------------------------------------------------------------------
    }
  }
  tg.onInput(DlgTeam.INPUT_AGE.id) { msg ->
    val age = msg.toIntOrNull() ?: run {
      // ----------------------------------------------------------------------------------
      tg.reply("Введите просто число")
      // ----------------------------------------------------------------------------------
      return@onInput
    }
    val stateJson = tg.dialogState?.asJson() ?: run {
      // ----------------------------------------------------------------------------------
      tg.reply("Кажется, что-то пошло не так, я потерял контекст разговора")
      // ----------------------------------------------------------------------------------
      return@onInput
    }
    db {
      tg.userSession.save(DlgTeam.CREATE.id, stateJson.put("age", age).toString())
      // ----------------------------------------------------------------------------------
      tg.reply("""Ваш новый товарищ\:
        |Имя\: ${stateJson["name"]?.asText("")?.escapeMarkdown()}
        |Возраст\: $age
      """.trimMargin(),
        isMarkdown = true,
        buttons = listOf(
          BtnData("Создать", OBJECT_MAPPER.createObjectNode().apply {
            setSection(CbSection.TEAM)
            setCommand(CbTeamCommand.CREATE)
          }.toString()),
          BtnData("Отменить", createCancelAddDialogCallback())
        )
      )
      // ----------------------------------------------------------------------------------
    }
  }

}

fun landing(tg: ChainBuilder, isInplaceUpdate: Boolean, node: ObjectNode) {
  db {
    val list = selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(tg.userId)).map {
      """${it.followerDisplayName!!.escapeMarkdown()}, ${it.followerAge}"""
    }.joinToString(separator = "\n")
    tg.reply("""*Ваша команда*:
            |${"-".repeat(20).let {it.escapeMarkdown()}}
            |$list
          """.trimMargin(),
      isMarkdown = true,
      buttons = listOf(
        BtnData("Добавить участника", node.deepCopy().put(CB_COMMAND, CbTeamCommand.ADD_DIALOG.id).toString()),
        BtnData("<< Назад", node.deepCopy().apply {
          setSection(CbSection.LANDING)
        }.toString())
      ),
      isInplaceUpdate = isInplaceUpdate
    )
  }
}

fun ParticipantRecord.teamMembers(): List<ParticipantRecord> =
  db {
    selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(this@teamMembers.userId)).map {
      ParticipantRecord(id = it.followerId, age = it.followerAge, displayName = it.followerDisplayName)
    }
  }


private fun createCancelAddDialogCallback() = OBJECT_MAPPER.createObjectNode().apply {
  setSection(CbSection.TEAM)
  setCommand(CbTeamCommand.LANDING)
}.toString()

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let {CbTeamCommand.entries[it]} ?: CbTeamCommand.LANDING
private fun ObjectNode.setCommand(command: CbTeamCommand) = this.put("c", command.id)