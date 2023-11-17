package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAM
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAMVIEW
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok

enum class CbTeamCommand {
  LANDING, ADD_DIALOG, RESET;
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
        // Handled in the dialog code below
      }
      CbTeamCommand.RESET -> {
        tg.userSession.reset()
      }
    }
  }
  tg.dialog(
    id = CbTeamCommand.ADD_DIALOG.id,
    intro = """
      Создаём нового члена вашей команды\. 
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {
    trigger = json {
      setSection(CbSection.TEAM)
      setCommand(CbTeamCommand.ADD_DIALOG)
    }
    exitPayload = json {
      setSection(CbSection.TEAM)
      setCommand(CbTeamCommand.LANDING)
    }
    step("name", DialogDataType.TEXT, "Имя", "Как зовут товарища?")
    step("age", DialogDataType.INT, "Возраст", "Сколько ему/ей лет? [просто число]",
      validatorReply = "Введите неотрицательное число арабскими цифрами. Например: 13"
    )
    confirm("Создаём?") {json ->
      val newDisplayName = json["name"]?.asText() ?: return@confirm Err("Не указано имя")
      val newAge = json["age"]?.asInt() ?: return@confirm Err("Не указан возраст")

      db {
        val newParticipant = insertInto(PARTICIPANT).columns(PARTICIPANT.DISPLAY_NAME, PARTICIPANT.AGE)
          .values(newDisplayName, newAge).returning().fetchOne()
        insertInto(PARTICIPANTTEAM).columns(PARTICIPANTTEAM.LEADER_ID, PARTICIPANTTEAM.FOLLOWER_ID)
          .values(participant.id, newParticipant!!.id).execute()
      }
      tg.userSession.reset()
      tg.reply("Товарищ записан в вашу команду. Теперь вы можете регистрировать его во всех доступных вам событиях")
      return@confirm Ok(json)
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


private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let {CbTeamCommand.entries[it]} ?: CbTeamCommand.LANDING
private fun ObjectNode.setCommand(command: CbTeamCommand) = this.put("c", command.id)