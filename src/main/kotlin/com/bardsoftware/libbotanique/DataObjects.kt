package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.michaelbull.result.Result

/**
 * Data object that carries information about the Telegram user who sent a message.
 */
data class TgUser(val displayName: String, val id: String, val username: String)

/**
 * Data object that puts together the information about the user's input, including state, context, user, and command.
 */
data class InputEnvelope(val stateJson: ObjectNode, val contextJson: ObjectNode, val user: TgUser, val command: String? = null, val contents: InputContents = InputVoid())

/**
 * Data object that represents a document, including its ID, caption, and download function.
 */
data class Document(val docId: String, val caption: String = "", val download: (Document)-> Result<ByteArray, String>)

/**
 * Different types of user input. It may be a command, a list of photos, or a state passed from the callback buttons.
 */
sealed class InputContents
class InputVoid: InputContents()
data class InputCommand(val command: String) : InputContents()
data class InputPhotoList(val docs: List<Document>) : InputContents()
data class InputState(val state: ObjectNode) : InputContents()

data class OutputData(val contextJson: ObjectNode)
