package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.records.ParticipantteamviewRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAM
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAMVIEW
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok

enum class CbTeamCommand {
  LANDING, ADD_DIALOG, RESET;
  val id get() = this.ordinal
}

fun ParticipantRecord.teamManagementCallbacks(tg: ChainBuilder) {
  val participant = this
  tg.dialog(
    id = CbTeamCommand.ADD_DIALOG.id,
    intro = """
      Создаём нового члена вашей команды\. 
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {

    setup = {
      exitPayload = it.get("esc")?.toString() ?: json {
        setSection(CbSection.TEAM)
        setCommand(CbTeamCommand.LANDING)
      }
      it
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
      tg.reply("Товарищ записан в вашу команду. Теперь вы можете регистрировать его во всех доступных вам событиях",
        buttons = listOf(escapeButton)
      )
      tg.userSession.reset()
      return@confirm Ok(json)
    }
  }
}

class TeamLandingAction(private val teamMemberList: List<ParticipantteamviewRecord>): StateAction {
  constructor(inputData: InputData): this(
    db {
      selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(inputData.user.id.toLong())).toList()
    }
  )

  override val text = TextMessage(
    """*Ваша команда*:
    |${"-".repeat(20).escapeMarkdown()}
    |${teamMemberList.map{"${it.followerDisplayName!!.escapeMarkdown()}, ${it.followerAge}"}.joinToString(separator = "\n")}
    """.trimMargin(), TextMarkup.MARKDOWN
  )

  override val buttonTransition get() = ButtonTransition(listOf(
    "TEAM_MEMBER_ADD" to ButtonBuilder({"Добавить участника..."}) {OutputData(
      objectNode {
        setSection(CbSection.DIALOG)
        setDialogId(CbTeamCommand.ADD_DIALOG.id)
      }
    )},
    "PARTICIPANT_LANDING" to ButtonBuilder({"<< Назад"})
  ))
}

fun ParticipantRecord.teamMembers(): List<ParticipantRecord> =
  db {
    selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(this@teamMembers.userId)).map {
      ParticipantRecord(id = it.followerId, age = it.followerAge, displayName = it.followerDisplayName)
    }
  }


private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let {CbTeamCommand.entries[it]} ?: CbTeamCommand.LANDING
fun ObjectNode.setCommand(command: CbTeamCommand) = this.put("c", command.id)