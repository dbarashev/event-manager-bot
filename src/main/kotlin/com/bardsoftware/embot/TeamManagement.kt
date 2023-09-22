package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAM
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAMVIEW

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
    val section = node[CB_SECTION]?.asInt() ?: return@onCallback
    if (section != CbSection.TEAM.ordinal) {
      return@onCallback
    }
    println("This is a team callback")

    val command = node["c"]?.asInt()?.let {CbTeamCommand.entries[it]} ?: CbTeamCommand.LANDING
    println("The command is: $command")
    when (command) {
      CbTeamCommand.LANDING -> {
        db {
          val list = selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(tg.userId)).map {
            """${it.followerDisplayName!!.escapeMarkdown()}, ${it.followerAge}
              |
            """.trimMargin()
          }
          tg.reply("""*Ваша команда*\:
            |${"\\-".repeat(20)}
            |$list
          """.trimMargin(), isMarkdown = true, buttons = listOf(
            BtnData("Добавить участника", node.put(CB_COMMAND, CbTeamCommand.ADD_DIALOG.id).toString())
          )
          )
        }
      }
      CbTeamCommand.ADD_DIALOG -> {
        db {
          dialogState(participant.userId!!, DlgTeam.INPUT_NAME.id, "")
        }
        tg.reply("Как зовут участника?")
      }
      CbTeamCommand.RESET -> {
        db {
          tg.fromUser?.resetDialog()
        }
      }
      CbTeamCommand.CREATE -> {
        tg.fromUser?.getDialogState()?.asJson()?.let { stateJson ->
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
          tg.fromUser.resetDialog()
          tg.reply("Товарищ записан в вашу команду. Теперь вы можете регистрировать его во всех доступных вам событиях")
        }
      }
    }
  }
}

fun ParticipantRecord.teamMemberDialog(tg: ChainBuilder) {
  val participant = this
  tg.onInput(DlgTeam.INPUT_NAME.id) {msg ->
    db {
      dialogState(participant.userId!!, DlgTeam.INPUT_AGE.id,
        OBJECT_MAPPER.createObjectNode().put("name", msg.trim()).toString()
      )
      tg.reply("Сколько ему лет? [введите число]")
    }
  }
  tg.onInput(DlgTeam.INPUT_AGE.id) { msg ->
    val age = msg.toIntOrNull() ?: run {
      tg.reply("Введите просто число")
      return@onInput
    }
    val stateJson = tg.dialogState?.asJson() ?: run {
      tg.reply("Кажется, что-то пошло не так, я потерял контекст разговора")
      return@onInput
    }
    db {
      dialogState(participant.userId!!, DlgTeam.CREATE.id, stateJson.put("age", age).toString())
      tg.reply("""Ваш новый товарищ\:
        |Имя\: ${stateJson["name"]?.asText("")?.escapeMarkdown()}
        |Возраст\: $age
      """.trimMargin(), isMarkdown = true, buttons = listOf(
        BtnData("Создать", OBJECT_MAPPER.createObjectNode().also {
          it.put(CB_SECTION, CbSection.TEAM.id).put(CB_COMMAND, CbTeamCommand.CREATE.id)
        }.toString()),
        BtnData("Отменить", createCancelAddDialogCallback())
      )
      )
    }
  }

}

fun createCancelAddDialogCallback() = OBJECT_MAPPER.createObjectNode().also {
  it.put(CB_SECTION, CbSection.TEAM.id).put(CB_COMMAND, CbTeamCommand.RESET.id)
}.toString()