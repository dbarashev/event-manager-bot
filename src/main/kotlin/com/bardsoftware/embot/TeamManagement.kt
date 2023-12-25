package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAM
import com.bardsoftware.embot.db.tables.references.PARTICIPANTTEAMVIEW
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onSuccess

enum class CbTeamCommand {
  LANDING, ADD_DIALOG, MEMBER_INFO, MEMBER_DELETE, EDIT_LIST, EDIT_DIALOG, RESET;
  val id get() = this.ordinal
}

private fun participantInfoDialog(tg: ChainBuilder, registratorId: Int, command: CbTeamCommand, setupCode: (ObjectNode)->ObjectNode) {
  tg.dialog(
    id = command.id,
    intro = """
      Создаём нового члена вашей команды\. 
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {

    setup = {
      exitPayload = it.get("esc")?.toString() ?: json {
        setSection(CbSection.TEAM)
        setCommand(CbTeamCommand.LANDING)
      }
      it.setTeamLeaderId(registratorId)
      setupCode(it)
    }

    step("name", DialogDataType.TEXT, "Имя", "Как зовут товарища?")
    step("age", DialogDataType.INT, "Возраст", "Сколько ему/ей лет? [просто число]",
      validatorReply = "Введите неотрицательное число арабскими цифрами. Например: 13"
    )
    confirm("Создаём/обновляем?") {json ->
      createTeamMember(json).onSuccess {teamMemberId ->
        tg.reply("Готово", buttons = listOf(
          BtnData("<< Назад", callbackData = json(exitPayload.asJson()!!) {
            setTeamMemberId(teamMemberId)
          })
        ), isInplaceUpdate = true)
        tg.userSession.reset(command.id)
        Ok(json)
      }
    }
  }
}

fun createTeamMember(json: ObjectNode): Result<Int, String> {
  val registratorId = json.getTeamLeaderId() ?: return Err("Не указан ID команды")
  val newDisplayName = json["name"]?.asText() ?: return Err("Не указано имя")
  val newAge = json["age"]?.asInt() ?: return Err("Не указан возраст")
  return json.getTeamMemberId()?.let {followerId ->
    txn {
      update(PARTICIPANT)
        .set(PARTICIPANT.DISPLAY_NAME, newDisplayName)
        .set(PARTICIPANT.AGE, newAge)
        .where(PARTICIPANT.ID.eq(followerId))
        .execute()
      return@txn Ok(followerId)
    }
  } ?: run {
    txn {
      val newParticipant = insertInto(PARTICIPANT).columns(PARTICIPANT.DISPLAY_NAME, PARTICIPANT.AGE)
        .values(newDisplayName, newAge).returning().fetchOne() ?: return@txn Err("Не получилось обновить данные")
      insertInto(PARTICIPANTTEAM).columns(PARTICIPANTTEAM.LEADER_ID, PARTICIPANTTEAM.FOLLOWER_ID)
        .values(registratorId, newParticipant.id)
        .execute()
      return@txn Ok(newParticipant.id!!)
    }
  }
}
fun ParticipantRecord.teamManagementCallbacks(tg: ChainBuilder) {
  participantInfoDialog(tg, this.id!!, CbTeamCommand.ADD_DIALOG) { it }
  participantInfoDialog(tg, this.id!!, CbTeamCommand.EDIT_DIALOG) {input ->
    input.getTeamMemberId()?.let(::getParticipant)?.let { participant ->
      input.apply {
        setDialogId(CbTeamCommand.EDIT_DIALOG.id)
        put("name", participant.displayName)
        put("age", participant.age)
        setTeamMemberId(participant.id!!)
      }
    } ?: input
  }
}

class TeamLandingAction(private val teamMemberList: List<ParticipantRecord>, private val leaderId: Int): StateAction {
  constructor(inputData: InputData): this(
    teamMembers(inputData.user.id.toLong()),
    getParticipant(inputData.user.id.toLong())?.id ?: throw RuntimeException("Can't find participant record in the database")
  )

  override val text = TextMessage(
    """*Ваша команда*
    |${"-".repeat(20).escapeMarkdown()}
    |
    |Здесь вы можете добавить нового члена команды, или отредактировать информацию об уже имеющемся\.
    """.trimMargin(), TextMarkup.MARKDOWN
  )

  override val buttonTransition get() = ButtonTransition(
    teamMemberList.map {record ->
      "TEAM_MEMBER_INFO" to ButtonBuilder({record.displayName!!}) {
        OutputData(objectNode {
          setTeamMemberId(record.id!!)
          setTeamLeaderId(leaderId)
        })
      }
    } + listOf(
    "TEAM_MEMBER_ADD" to ButtonBuilder({"Добавить участника..."}) {OutputData(
      objectNode {
        setSection(CbSection.DIALOG)
        setDialogId(CbTeamCommand.ADD_DIALOG.id)
      }
    )},
    "PARTICIPANT_LANDING" to ButtonBuilder({"<< Назад"})
  ))
}

class TeamMemberInfoAction(teamMember: ParticipantRecord, private val leaderId: Int): StateAction {
  constructor(inputData: InputData): this(
    db {
      inputData.contextJson.getTeamMemberId()?.let(::getParticipant) ?: throw RuntimeException("Can't find a team member record in the database")
    },
    inputData.contextJson.getTeamLeaderId() ?: throw RuntimeException("Can't find a team leader field in the context")
  )


  override val text = TextMessage("""
    *Имя*\: ${teamMember.displayName!!.escapeMarkdown()}
    *Возраст*\: ${teamMember.age!!}
  """.trimIndent(), TextMarkup.MARKDOWN)
  override val buttonTransition = ButtonTransition(
    buttons = listOf(
      "TEAM_EDIT_DIALOG" to ButtonBuilder({"Редактировать..."}) {
        OutputData(objectNode {
          setSection(CbSection.DIALOG)
          setTeamMemberId(teamMember.id!!)
          setDialogId(CbTeamCommand.EDIT_DIALOG.id)
        })},
      "TEAM_MEMBER_DELETE" to ButtonBuilder({"Удалить"}) {
        OutputData(objectNode {
          setTeamMemberId(teamMember.id!!)
          setTeamLeaderId(leaderId)
        })},
      "TEAM_LANDING" to ButtonBuilder({"<< Назад"})
    )
  )
}


fun ParticipantRecord.teamMembers() = teamMembers(this.userId!!)

fun teamMembers(leaderId: Long) =
  db {
    selectFrom(PARTICIPANTTEAMVIEW).where(PARTICIPANTTEAMVIEW.LEADER_USER_ID.eq(leaderId)).map {
      ParticipantRecord(id = it.followerId, age = it.followerAge, displayName = it.followerDisplayName)
    }
  }

fun deleteTeamMember(id: Int, leaderId: Int) = txn {
  deleteFrom(PARTICIPANTTEAM).where(PARTICIPANTTEAM.FOLLOWER_ID.eq(id).and(PARTICIPANTTEAM.LEADER_ID.eq(leaderId))).execute()
}
private fun getParticipant(id: Int) = db {
  selectFrom(PARTICIPANT).where(PARTICIPANT.ID.eq(id)).fetchOne()
}

private fun getParticipant(tgUserId: Long) = db {
  selectFrom(PARTICIPANT).where(PARTICIPANT.USER_ID.eq(tgUserId)).fetchOne()
}
private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let {CbTeamCommand.entries[it]} ?: CbTeamCommand.LANDING
fun ObjectNode.setCommand(command: CbTeamCommand) = this.put("c", command.id)

fun ObjectNode.getTeamMemberId() = this["tmid"]?.asInt()
fun ObjectNode.setTeamMemberId(id: Int) = this.put("tmid", id)

fun ObjectNode.getTeamLeaderId() = this["tlid"]?.asInt()
private fun ObjectNode.setTeamLeaderId(id: Int) = this.put("tlid", id)