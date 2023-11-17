package com.bardsoftware.embot

import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.bardsoftware.libbotanique.OBJECT_MAPPER
import com.bardsoftware.libbotanique.escapeMarkdown
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Result
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class DialogDataType {
  TEXT, INT, NUMBER, DATE
}

data class DialogStep(
  val fieldName: String,
  val question: String,
  val dataType: DialogDataType,
  val shortLabel: String,
  val invalidValueReply: String = ""
)

data class Dialog(val tg: ChainBuilder, val id: Int, val intro: String) {
  private val steps = mutableListOf<DialogStep>()

  var exitPayload: String = ""
  var trigger: String = "{}"
  var setup: (ObjectNode) -> ObjectNode = { jacksonObjectMapper().createObjectNode() }

  fun step(id: String, type: DialogDataType, shortLabel: String, prompt: String, validatorReply: String = "") {
    steps.add(DialogStep(id, prompt, type, shortLabel, validatorReply))
  }

  fun confirm(question: String, onOk: (ObjectNode)-> Result<Any, String>) {
    createDialog(question) {
      onOk(it)
    }
  }

  private fun replyStep(dialogData: ObjectNode, stepIdx: Int) {
    dialogData.put("next_field", steps[stepIdx].fieldName)
    tg.userSession.save(OrgManagerCommand.EVENT_ADD.id, dialogData.toString())
    tg.reply(steps[stepIdx].question, buttons = listOf(
      BtnData("<< Выйти", callbackData = exitPayload)
    ))

  }
  fun createDialog(confirmQuestion: String, onSuccess: (ObjectNode) -> Unit) {
    tg.onCallback { node ->
      jacksonObjectMapper().readTree(trigger).let {
        val triggerSet = it.fields().asSequence().toSet()
        val nodeSet = node.fields().asSequence().toSet()
        if (nodeSet.containsAll(triggerSet)) {
          replyStep(setup(node), 0)
          return@onCallback
        }
      }
      if (node.getDialogId() == this@Dialog.id) {
        if (node["c"]?.asBoolean() == true) {
          val eventData = tg.userSession.state?.asJson() ?: OBJECT_MAPPER.createObjectNode()
          onSuccess(eventData)
          tg.reply("Готово!", buttons = listOf(
            BtnData("<< Назад", callbackData = exitPayload)
          ))
        }
      }
    }
    tg.onInput(this.id) { msg ->
      val dialogData = tg.userSession.state?.asJson() ?: OBJECT_MAPPER.createObjectNode()
      println(dialogData)
      val expectedField = dialogData["next_field"]?.asText() ?: steps[0].fieldName
      val expectedStepIdx = steps.indexOfFirst { it.fieldName == expectedField }
      if (expectedStepIdx == -1) {
        return@onInput
      }
      val expectedStep = steps[expectedStepIdx]
      val isValueValid = when(expectedStep.dataType) {
        DialogDataType.TEXT -> true
        DialogDataType.INT -> msg.toIntOrNull() != null
        DialogDataType.NUMBER -> msg.toBigDecimalOrNull() != null
        DialogDataType.DATE -> msg.toDate().isSuccess
      }
      if (!isValueValid) {
        println("value $msg is not valid, reply: ${expectedStep.invalidValueReply}")
        tg.reply(expectedStep.invalidValueReply.ifBlank { "Значение не валидно" })
        return@onInput
      }
      dialogData.put(expectedStep.fieldName, msg)
      if (expectedStepIdx + 1 < steps.size) {
        replyStep(dialogData, expectedStepIdx + 1)
      } else {
        tg.userSession.save(OrgManagerCommand.EVENT_ADD.id, dialogData.toString())
        val summary = steps.joinToString(separator = "\n") {
          "*${it.shortLabel.escapeMarkdown()}*\\: ${dialogData[it.fieldName]?.asText()?.escapeMarkdown() ?: "\\-"}"
        }
        tg.reply("""${confirmQuestion.escapeMarkdown()}
        |
        |$summary
      """.trimMargin(),
          buttons = listOf(
            BtnData("Да", callbackData = json {
              setSection(CbSection.MANAGER)
              setDialogId(this@Dialog.id)
              put("c", true)
            }),
            BtnData("Отменить", callbackData = exitPayload)
          ),
          isMarkdown = true
        )
      }
    }
  }
}

fun ChainBuilder.dialog(id: Int, intro: String, builder: Dialog.()->Unit) {
  Dialog(this, id, intro).apply(builder)
}

fun ObjectNode.getDialogId() = this["d"]?.asInt()
private fun ObjectNode.setDialogId(dialogId: Int) = this.put("d", dialogId)

fun String.toDate() =
  Result.runCatching { LocalDate.parse(this@toDate, DateTimeFormatter.ISO_DATE) }

fun json(prototype: ObjectNode = jacksonObjectMapper().createObjectNode(),
         builder: ObjectNode.() -> Unit) = prototype.deepCopy().apply(builder).toString()
