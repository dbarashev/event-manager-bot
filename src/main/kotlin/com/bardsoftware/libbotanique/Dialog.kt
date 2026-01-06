package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import java.math.BigDecimal

fun State.dialog(dialogBuilder: Dialog.()->Unit) {
    val dlg = Dialog(this).apply(dialogBuilder)
    action(dlg::process)
}

class Dialog(internal val initialState: State) {
    private val fields = mutableListOf<DialogStep>()
    private var idxCurrentField = 0
    private val dialogData: ObjectNode by lazy {
        userSession().load(initialState.id)?.asJson() ?: jacksonObjectMapper().createObjectNode()
    }
    private var userSession: ()->UserSessionStorage = { error("No user session provider") }
    fun intro(intro: TextMessage, buttonLabel: String = "Начать") {
        fields.add(DialogStep(fieldName = "start", contents = intro, shortLabel = buttonLabel, dataType = DialogDataType.START))
    }

    fun apply(question: TextMessage, confirmation: TextMessage, successCode: (ObjectNode)->Unit) {
        fields.add(DialogStep(fieldName = "confirm", contents = question, dataType =  DialogDataType.BOOLEAN, field = ConfirmationField("confirm"){
            successCode(it)
            userSession().reset(initialState.id)
        }, invalidValueReply = "Изменения отменены"))
        fields.add(DialogStep(fieldName = "exit", contents = confirmation, shortLabel = "< Выход", dataType = DialogDataType.CONFIRMATION,
            field = ExitField()))
    }

    internal fun process(inputEnvelope: InputEnvelope): StateAction {
        if (fields.isEmpty()) return SimpleAction("ПУСТОЙ ДИАЛОГ", initialState.stateMachine.landingStateId, inputEnvelope) {}

        userSession = { initialState.stateMachine.sessionProvider(inputEnvelope.user.id.toLong()) }
        idxCurrentField = fields.indexOfFirst { it.fieldName == inputEnvelope.contextJson.getCurrentField() }
        return process(idxCurrentField, inputEnvelope)
    }

    private fun process(idxCurrentField: Int, inputEnvelope: InputEnvelope): StateAction {
        if (idxCurrentField >= 0) {
            val step = fields[idxCurrentField]
            val isValueValid = step.field.validate(inputEnvelope)
            if (!isValueValid) {
                return SimpleAction(
                    step.invalidValueReply.ifBlank { "Значение не валидно" },
                    initialState.stateMachine.landingStateId,
                    inputEnvelope
                ) {}
            }
            step.field.processInput(inputEnvelope, dialogData)
            saveDialogData()
        }

        val nextStep = fields[idxCurrentField + 1]
        return when (nextStep.dataType) {
            DialogDataType.START -> {
                ButtonsAction(nextStep.contents, listOf(
                    initialState.id to button(nextStep.shortLabel) {
                        setNextField(nextStep.fieldName)
                    })
                )
            }
            DialogDataType.BOOLEAN -> {
                ButtonsAction(nextStep.contents, listOf(
                    initialState.id to button("Yes") {
                        setNextField(nextStep.fieldName)
                        put("y", 1)
                    },
                    initialState.id to button("No") {
                        setNextField(nextStep.fieldName)
                        put("y", 0)
                    }
                ))
            }
            DialogDataType.CONFIRMATION -> {
                SimpleAction("Изменения записаны", initialState.stateMachine.landingStateId, inputEnvelope) {}
            }
            else -> {
                SimpleAction("FIELD ${nextStep.fieldName} NOT IMPLEMENTED", initialState.stateMachine.landingStateId, inputEnvelope) {}
            }
        }
    }

    private fun saveDialogData() {
        userSession().save(this.initialState.id, dialogData.toString())
    }

}

enum class DialogDataType {
    START, TEXT, INT, NUMBER, DATE, LOCATION, BOOLEAN, CONFIRMATION
}

data class LatLon(val lat: BigDecimal, val lon: BigDecimal) {
    override fun toString() = "$lat,$lon"

    fun asGoogleLink() = "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"
}

data class DialogStep(
    val fieldName: String,
    val question: String = "",
    val contents: TextMessage = TextMessage(question),
    val dataType: DialogDataType,
    val shortLabel: String = "",
    val invalidValueReply: String = "",
    val field: DialogField = VoidField(),
    val actionCode: (ObjectNode)->Unit = {}
)

sealed class DialogField {
    abstract fun processInput(envelope: InputEnvelope, dialogData: ObjectNode)
    open fun validate(inputEnvelope: InputEnvelope) = true
}

class VoidField : DialogField() {
    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode) {}
}

class ConfirmationField(private val fieldName: String, private val onSuccess: (ObjectNode) -> Unit) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contextJson.get("y").asText().let {
            it.toBool() is Ok
        }
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode) {
        onSuccess(dialogData)
    }
}

class ExitField() : DialogField() {
    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode) {
    }

    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contextJson.get("confirm").asText().toBool().getOrElse { false }
    }
}

private fun Dialog.button(label: String, payloadBuilder: ObjectNode.()->Unit): ButtonBuilder =
    ButtonBuilder({label}) {
        OutputData(objectNode {
            setAll<ObjectNode>(this@button.initialState.stateJson)
            payloadBuilder(this)
        })
    }
fun ObjectNode.getCurrentField() = this[">"]?.asText()
fun ObjectNode.setNextField(fieldId: String) = this.put(">", fieldId)
fun String.toBool() = Result.runCatching {
    this@toBool.trim().lowercase().let {
        if (setOf("1", "true", "y", "yes").contains(it)) {
            true
        } else if (setOf("0", "false", "no", "n").contains(it)) {
            false
        } else {
            throw IllegalArgumentException("Can't parse $it as boolean")
        }
    }
}
