package com.bardsoftware.embot

import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class DialogDataType {
  TEXT, INT, NUMBER, DATE, LOCATION
}

data class LatLon(val lat: BigDecimal, val lon: BigDecimal) {
  override fun toString() = "$lat,$lon"

  fun asGoogleLink() = "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"
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

  var exitPayload: String = "{}"

  val dialogData: ObjectNode by lazy {
    tg.userSession.state?.asJson()?.also {
      LOG_DIALOG.debug("..read dialog data={}", it)
      exitPayload = it["esc"].toString()
    }
    ?: jacksonObjectMapper().createObjectNode() }
  private fun saveDialogData(node: ObjectNode = dialogData) {
    tg.userSession.save(this@Dialog.id, node.apply {
      set<ObjectNode>("esc", exitPayload.asJson())
    }.toString())
  }
  var setup: (ObjectNode) -> ObjectNode = { jacksonObjectMapper().createObjectNode() }

  val escapeButton get() = BtnData("<< Назад", callbackData = exitPayload)

  fun step(id: String, type: DialogDataType, shortLabel: String, prompt: String, validatorReply: String = "") {
    steps.add(DialogStep(id, prompt, type, shortLabel, validatorReply))
  }

  fun confirm(question: String, onOk: (ObjectNode)-> Result<Any, String>) {
    createDialog(question) {
      onOk(it)
    }
  }

  private fun replyStep(dialogData: ObjectNode, stepIdx: Int) {
    dialogData.apply {
      put("next_field", steps[stepIdx].fieldName)
      set<ObjectNode>("esc", exitPayload?.asJson())
    }
    saveDialogData()
    val prefixButtons = dialogData[steps[stepIdx].fieldName]?.let {
      listOf(BtnData("Пропустить", callbackData = json {
        setSection(CbSection.DIALOG)
        setDialogId(this@Dialog.id)
        setDialogOk()
        put("next_field", stepIdx)
      }))
    } ?: emptyList()
    val defaultValue = dialogData[steps[stepIdx].fieldName]?.asText()?.let {
      "[${it}]"
    } ?: ""

    tg.reply("${steps[stepIdx].question} $defaultValue",
      buttons = prefixButtons + listOf(BtnData("<< Выйти", callbackData = exitPayload ?: "{}"))
    )

  }


  fun createDialog(confirmQuestion: String, onSuccess: (ObjectNode) -> Unit) {
    tg.onCallback { node ->
      LOG_DIALOG.debug("This dialog ID={}, dialog ID in the input is {}", this.id, node.getDialogId())
      if (node.getDialogId() == this@Dialog.id) {
        if (node.hasDialogOk()) {
          LOG_DIALOG.debug("..user clicked Ok or Next")
          node["next_field"]?.asInt()?.let { expectedFieldIdx ->
            // Skip this field
            LOG_DIALOG.debug("..user clicked next, so we'll skip this step. Expected next step is {}", expectedFieldIdx)
            LOG_DIALOG.debug("..dialog data: {}", dialogData)
            nextStep(confirmQuestion, expectedFieldIdx, dialogData)
            return@onCallback
          }
          LOG_DIALOG.debug("..user clicked Ok in the confirmation dialog. Dialog data={}", dialogData)
          onSuccess(dialogData)
          return@onCallback
        } else {
          LOG_DIALOG.debug("..dialog just've started.")
          replyStep(setup(node).also {saveDialogData(it)}, 0)
        }
      } else {
        LOG_DIALOG.debug("... input is not applicable")
      }
    }
    tg.onInput(this.id) { msg ->
      LOG_DIALOG.debug("Received text input from user: {}", msg)
      val expectedField = dialogData["next_field"]?.asText() ?: steps[0].fieldName
      val expectedStepIdx = steps.indexOfFirst { it.fieldName == expectedField }
      if (expectedStepIdx == -1) {
        return@onInput
      }
      LOG_DIALOG.debug("..this applies to field {}", expectedField)
      LOG_DIALOG.debug("..dialog data={}", dialogData)
      val expectedStep = steps[expectedStepIdx]
      val isValueValid = when(expectedStep.dataType) {
        DialogDataType.TEXT -> true
        DialogDataType.INT -> msg.toIntOrNull()?.takeIf { it >= 0 } != null
        DialogDataType.NUMBER -> msg.toBigDecimalOrNull() != null
        DialogDataType.DATE -> msg.toDate() is Ok
        DialogDataType.LOCATION -> msg.toLatLon() is Ok
      }
      if (!isValueValid) {
        println("value $msg is not valid, reply: ${expectedStep.invalidValueReply}")
        tg.reply(expectedStep.invalidValueReply.ifBlank { "Значение не валидно" })
        return@onInput
      }
      dialogData.put(expectedStep.fieldName, msg)
      nextStep(confirmQuestion, expectedStepIdx, dialogData)
    }
  }

  private fun nextStep(confirmQuestion: String, expectedStepIdx: Int, dialogData: ObjectNode) {
    if (expectedStepIdx + 1 < steps.size) {
      replyStep(dialogData, expectedStepIdx + 1)
    } else {
      saveDialogData()
      val summary = steps.joinToString(separator = "\n") {
        "*${it.shortLabel.escapeMarkdown()}*\\: ${dialogData[it.fieldName]?.asText()?.escapeMarkdown() ?: "\\-"}"
      }
      tg.reply("""${confirmQuestion.escapeMarkdown()}
        |
        |$summary
      """.trimMargin(),
        buttons = listOf(
          BtnData("Да", callbackData = json {
            setSection(CbSection.DIALOG)
            setDialogId(this@Dialog.id)
            setDialogOk()
          }),
          BtnData("Отменить", callbackData = exitPayload ?: "{}")
        ),
        isMarkdown = true
      )
    }
  }
}

fun ChainBuilder.dialog(id: Int, intro: String, builder: Dialog.()->Unit) {
  Dialog(this, id, intro).apply(builder)
}

fun ObjectNode.getDialogId() = this["d"]?.asInt()
fun ObjectNode.setDialogId(dialogId: Int) = this.put("d", dialogId)
fun ObjectNode.hasDialogOk() = this["ok"]?.asBoolean() == true
fun ObjectNode.setDialogOk() = this.put("ok", true)
fun String.toDate() = Result.runCatching {
  LocalDateTime.parse(this@toDate.trim().replace(' ', 'T'), DateTimeFormatter.ISO_DATE_TIME)
}
fun String.toLatLon() = Result.runCatching {
  val (strLat, strLon) = this@toLatLon.trim().split(regex = """\s*,\s*""".toRegex(), limit=2)
  LatLon(strLat.toBigDecimal(), strLon.toBigDecimal())
}.onFailure { it.printStackTrace() }

fun json(prototype: ObjectNode = jacksonObjectMapper().createObjectNode(),
         builder: ObjectNode.() -> Unit) = prototype.deepCopy().apply(builder).toString()

private val LOG_DIALOG = LoggerFactory.getLogger("Bot.Dialog")