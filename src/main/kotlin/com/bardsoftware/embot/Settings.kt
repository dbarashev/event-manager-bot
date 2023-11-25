package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.PARTICIPANT
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError

enum class SettingsCommand {
  LANDING, CHANGE;

  val id get() = 150 + ordinal
  companion object {
    fun find(id: Int) = entries.firstOrNull { it.id == id }
  }
}

enum class SettingsField {
  NAME, AGE, PHONE
}
fun ParticipantRecord.settingsModule(tg: ChainBuilder) {
  val participant = this
  tg.onCallback { node ->
    val section = node.getSection()
    if (section != CbSection.SETTINGS) {
      return@onCallback
    }

    when (node.getCommand()) {
      SettingsCommand.LANDING -> {
        tg.reply("""Ваш профиль:
          | 
          | Имя: ${participant.displayName}
          | Возраст: ${participant.age}
          | Телефон: ${participant.phone}
        """.trimMargin(),
          buttons = listOf(
            BtnData("Поменять", callbackData = json(node) {
              setCommand(SettingsCommand.CHANGE)
            }),
            returnToFirstLanding()
          ), isInplaceUpdate = true, isMarkdown = false
        )
      }
      SettingsCommand.CHANGE -> {
      }
    }
  }

  tg.dialog(
    id = SettingsCommand.CHANGE.id,
    intro = """
      Редактируем ваш профиль\. 
      
      _Процесс можно прервать в любой момент кнопкой "Отменить"_
      """.trimIndent()) {

    trigger = json {
      setSection(CbSection.SETTINGS)
      setCommand(SettingsCommand.CHANGE)
    }
    setup = { input -> jacksonObjectMapper().createObjectNode().apply {
      put("participant_id", participant.id)
      put("name", participant.displayName)
      put("phone", participant.phone)
    }}
    exitPayload = json {
      setSection(CbSection.SETTINGS)
      setCommand(SettingsCommand.LANDING)
    }
    step("name", DialogDataType.TEXT, "Имя", "Ваше имя:")
    step("phone", DialogDataType.TEXT, "Телефон", "Контактный телефон:")
    confirm("Сохранить изменения?") {json ->
      applySettings(json).mapError { " Что-то пошло не так" }
    }
  }

}

fun applySettings(json: ObjectNode): Result<Unit, Throwable> =
  txn {
    com.github.michaelbull.result.runCatching {
      update(PARTICIPANT).set(PARTICIPANT.DISPLAY_NAME, json["name"].asText()).set(PARTICIPANT.PHONE, json["phone"].asText())
        .where(PARTICIPANT.ID.eq(json["participant_id"].asInt()))
        .execute()
      Unit
    }
  }

private fun ObjectNode.getCommand() = this["c"]?.asInt()?.let(SettingsCommand::find) ?: SettingsCommand.LANDING
private fun ObjectNode.setCommand(cmd: SettingsCommand) = this.put("c", cmd.id)

private fun ObjectNode.getChangeField() = this["f"]?.asInt()?.let(SettingsField.entries::get)
private fun ObjectNode.setChangeField(field: SettingsField) = this.put("f", field.ordinal)