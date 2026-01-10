package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.*
import java.math.BigDecimal

/**
 * Represents a dialog with multiple steps for user interaction.
 * A dialog attaches to a state and manages user interactions through a series of steps. It saves the current dialog
 * state in the user session on the server side. A dialog state is a current field and the values entered in all previous fields.
 *
 * A dialog is created by calling the DSL methods. The DSL provides a fluent interface for defining dialog steps and
 * their behavior. Each step can have a label, a message, a data type, and a validation function.
 *
 * At the end, apply step should be called to define the final step of the dialog. It defines the confirmation message and
 * the success code that will be executed after the user confirms the dialog.
 */
class Dialog(internal val initialState: State) {
    private val fields = mutableListOf<DialogStep>()
    private var idxCurrentField = 0
    private val dialogData: ObjectNode by lazy {
        userSession().getDialogData(initialState.id)
    }
    private fun saveDialogData() {
        dialogData.put("idxField", idxCurrentField)
        userSession().saveDialogData(this.initialState.id, dialogData)
    }

    private var userSession: ()->UserSessionStorage = { error("No user session provider") }
    private var exitStateId = initialState.stateMachine.landingStateId

    fun matches(it: InputEnvelope): Boolean {
        return dialogData.has("idxField")
    }

    fun cancel(exitStateId: String) {
        this.exitStateId = exitStateId
    }

    fun intro(intro: TextMessage, buttonLabel: String = "Начать") {
        fields.add(DialogStep(fieldName = "start", contents = intro, shortLabel = buttonLabel, dataType = DialogDataType.START))
    }

    /**
     * Adds a video field. The dialog will be waiting for a video file and will call the supplied `onVideo` callback once it is
     * received.
     */
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
        fields.add(DialogStep(fieldName = "confirm", contents = question, dataType = DialogDataType.BOOLEAN,
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
        val inputState = if (idxCurrentField >= 0) {
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
        } else DialogInputState.WAITING

        if (inputState == DialogInputState.CANCEL) {
            userSession().reset(initialState.id)
            return SimpleAction(
                "Диалог отменен",
                exitStateId,
                inputEnvelope
            ) {
                dialogData.removeAll()
                it.contextJson.removeAll()
            }
        }
        if (inputState == DialogInputState.VALID) {
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
                            setNextField(nextStep.fieldName)
                            put("y", 1)
                    },
                    initialState.id to button(initialState.id, "No") {
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
}

enum class DialogDataType {
    START, TEXT, INT, NUMBER, DATE, LOCATION, BOOLEAN, CONFIRMATION, VIDEO
}

data class LatLon(val lat: BigDecimal, val lon: BigDecimal) {
    override fun toString() = "$lat,$lon"

    fun asGoogleLink() = "https://www.google.com/maps/search/?api=1&query=$lat%2C$lon"
}

enum class DialogInputState {
    WAITING, VALID, INVALID, CANCEL
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
    abstract fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState
    open fun validate(inputEnvelope: InputEnvelope) = true
}

class VoidField : DialogField() {
    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState = DialogInputState.VALID
}

class ConfirmationField(private val fieldName: String, private val onSuccess: (ObjectNode) -> Unit) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contextJson.get("y").asText().let {
            it.toBool() is Ok
        }
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState {
        if (envelope.contextJson.get("y").asText().toBool().getOrElse { false } != true) {
            return DialogInputState.CANCEL
        }
        onSuccess(dialogData)
        return DialogInputState.VALID
    }
}

class ExitField() : DialogField() {
    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState  = DialogInputState.VALID

    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contextJson.get("confirm").asText().toBool().getOrElse { false }
    }
}

class VideoField(private val onVideo: (ByteArray) -> Unit) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contents is InputVideo
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState {
        val video = envelope.contents as? InputVideo ?: return DialogInputState.INVALID
        return getMessageSender().download(video.doc)
            .map { bytes ->
                onVideo(bytes)
                // mark as processed; store optional marker
                dialogData.put("video_${video.doc.docId}", true)
                DialogInputState.VALID
            }
            .mapError {
                DialogInputState.INVALID
            }.get() ?: DialogInputState.INVALID
    }
}

class TextField(private val fieldName: String) : DialogField() {
    override fun validate(inputEnvelope: InputEnvelope): Boolean {
        return inputEnvelope.contents is InputText
    }

    override fun processInput(envelope: InputEnvelope, dialogData: ObjectNode): DialogInputState {
        val text = (envelope.contents as? InputText)?.text ?: return DialogInputState.INVALID
        dialogData.put(fieldName, text)
        return DialogInputState.VALID
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
fun UserSessionStorage.getDialogData(id: String) = (this.load(id)?.asJson()?.get("_") as? ObjectNode) ?: emptyNode()
fun UserSessionStorage.saveDialogData(id: String, data: ObjectNode) {
    val node = this.load(id)?.asJson() ?: emptyNode()
    node.put("_", data)
    this.save(id, node.toString())
}