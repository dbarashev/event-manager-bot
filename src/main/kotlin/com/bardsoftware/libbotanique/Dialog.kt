package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import java.math.BigDecimal

class Dialog(internal val initialState: State) {
    private val fields = mutableListOf<DialogStep>()
    private var idxCurrentField = 0
    private val dialogData: ObjectNode by lazy {
        userSession().load(initialState.id)?.asJson() ?: jacksonObjectMapper().createObjectNode()
    }
    private var userSession: ()->UserSessionStorage = { error("No user session provider") }

    fun matches(it: InputEnvelope): Boolean {
        return dialogData.has("idxField")
    }

    fun intro(intro: TextMessage, buttonLabel: String = "Начать") {
        fields.add(DialogStep(fieldName = "start", contents = intro, shortLabel = buttonLabel, dataType = DialogDataType.START))
    }

    // DSL: add a video step to the dialog
    fun video(fieldName: String, prompt: TextMessage, invalidValueReply: String = "Пришлите видео", onVideo: (ByteArray) -> Unit) {
        fields.add(
            DialogStep(
                fieldName = fieldName,
                contents = prompt,
                dataType = DialogDataType.VIDEO,
                invalidValueReply = invalidValueReply,
                field = VideoField(onVideo)
            )
        )
    }

    // DSL: add a text step to the dialog
    fun text(fieldName: String, prompt: TextMessage, invalidValueReply: String = "Пришлите текст") {
        fields.add(
            DialogStep(
                fieldName = fieldName,
                contents = prompt,
                dataType = DialogDataType.TEXT,
                invalidValueReply = invalidValueReply,
                field = TextField(fieldName)
            )
        )
    }

    fun apply(question: TextMessage, confirmation: TextMessage, successCode: (ObjectNode)->Unit) {
        fields.add(DialogStep(fieldName = "confirm", contents = question, dataType =  DialogDataType.BOOLEAN,
            field = ConfirmationField("confirm") {
                successCode(it)
                userSession().reset(initialState.id)
            },
            invalidValueReply = "Изменения отменены"
        ))
        fields.add(DialogStep(fieldName = "exit", contents = confirmation, shortLabel = "< Выход", dataType = DialogDataType.CONFIRMATION,
            field = ExitField()))
    }

    internal fun process(inputEnvelope: InputEnvelope): StateAction {
        if (fields.isEmpty()) return SimpleAction("ПУСТОЙ ДИАЛОГ", initialState.stateMachine.landingStateId, inputEnvelope) {}

        userSession = { initialState.stateMachine.sessionProvider(inputEnvelope.user.id.toLong()) }
        val currentFieldFromContext = inputEnvelope.contextJson.getCurrentField()
        val currentFieldFromSession = dialogData.getCurrentField()
        idxCurrentField = fields.indexOfFirst { it.fieldName == (currentFieldFromContext ?: currentFieldFromSession) }
        return process(idxCurrentField, inputEnvelope)
    }

    private fun process(idxCurrentField: Int, inputEnvelope: InputEnvelope): StateAction {
        // We process the user input using our current dialog field.
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

        // If the 
        val nextStep = fields[idxCurrentField + 1]
        return when (nextStep.dataType) {
            DialogDataType.START -> {
                ButtonsAction(nextStep.contents, listOf(
                    initialState.id to button(initialState.id, nextStep.shortLabel) {
                        setContext(objectNode {
                            setNextField(nextStep.fieldName)
                        })
                    })
                )
            }
            DialogDataType.VIDEO -> {
                // Remember that we expect a video for this step
                dialogData.setNextField(nextStep.fieldName)
                saveDialogData()
                SimpleAction(nextStep.contents.text, initialState.stateMachine.landingStateId, inputEnvelope) {}
            }
            DialogDataType.TEXT -> {
                // Remember that we expect a text for this step
                dialogData.setNextField(nextStep.fieldName)
                saveDialogData()
                SimpleAction(nextStep.contents.text, initialState.stateMachine.landingStateId, inputEnvelope) {}
            }
            DialogDataType.BOOLEAN -> {
                ButtonsAction(nextStep.contents, listOf(
                    initialState.id to button(initialState.id, "Yes") {
                        setContext(objectNode {
                            setNextField(nextStep.fieldName)
                            put("y", 1)
                        })
                    },
                    initialState.id to button(initialState.id, "No") {
                        setContext(objectNode {
                            setNextField(nextStep.fieldName)
                            put("y", 0)
                        })
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
        dialogData.put("idxField", idxCurrentField)
        userSession().save(this.initialState.id, dialogData.toString())
    }
}

enum class DialogDataType {
    START, TEXT, INT, NUMBER, DATE, LOCATION, BOOLEAN, CONFIRMATION, VIDEO
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

class VideoField(private val onVideo: (ByteArray) -> Unit) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contents is InputVideo
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode) {
        val video = envelope.contents as? InputVideo ?: return
        getMessageSender().download(video.doc)
            .onSuccess { bytes ->
                onVideo(bytes)
                // mark as processed; store optional marker
                dialogData.put("video_${video.doc.docId}", true)
            }
            .onFailure {
                // keep dialog as-is; validation already passed, but we won't crash
            }
    }
}

class TextField(private val fieldName: String) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contents is InputText
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode) {
        val text = (envelope.contents as? InputText)?.text ?: return
        dialogData.put(fieldName, text)
    }
}

private fun Dialog.button(targetStateId: String, label: String, payloadBuilder: ObjectNode.()->Unit): OutputButton {
    val targetState = initialState.stateMachine.getState(targetStateId)
    val payload = objectNode(targetState?.stateJson) {
        payloadBuilder(this)
    }.toString()
    return OutputButton(targetStateId, label = label, payload = payload)
}
fun ObjectNode.getCurrentField() = this[">"]?.asText()
fun ObjectNode.setNextField(fieldId: String) = this.put(">", fieldId)
fun ObjectNode.setContext(contextNode: ObjectNode) = this.put("_", contextNode)

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
