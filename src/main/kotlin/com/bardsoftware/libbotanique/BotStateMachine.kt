package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.*
import org.slf4j.LoggerFactory

data class TgUser(val displayName: String, val id: String, val username: String)

data class InputData(val stateJson: ObjectNode, val contextJson: ObjectNode, val user: TgUser, val command: String? = null)
data class OutputData(val contextJson: ObjectNode)

class State(val id: String, private val stateMachine: BotStateMachine) {
  private val requiredNode = jacksonObjectMapper().createObjectNode()
  private val requiredPredicates = mutableMapOf<String, (JsonNode)->Boolean>()

  val stateJson get() = requiredNode

  var isSubset: Boolean = false
  var isIgnored: Boolean = false
  var command: String? = null

  fun required(key: String, value: String) {
    requiredNode.put(key, value)
  }

  fun required(key: String, value: Int) {
    requiredNode.put(key, value)
  }

  fun required(jsonNode: ObjectNode) {
    requiredNode.setAll<ObjectNode>(jsonNode)
  }

  fun required(key: String, predicate: (JsonNode)->Boolean) {
    requiredPredicates[key] = predicate
  }

  fun matches(input: InputData): Boolean {
    if (isIgnored) return false
    this.command?.let {
      if (it == input.command) {
        return true
      }
    }
    val requiredSet = requiredNode.fields().asSequence().toSet()
    val nodeSet = input.stateJson.fields().asSequence().toSet()
    if (!nodeSet.containsAll(requiredSet)) {
      return false
    }
    if (requiredPredicates.any { !it.value(input.stateJson.get(it.key)) }) {
      return false
    }
    if (isSubset) {
      return true
    }
    return nodeSet.map { it.key }.toMutableSet().let {
      it.removeAll(requiredSet.map { it.key })
      it.removeAll(requiredPredicates.keys)
      it.isEmpty()
    }
  }

  fun action(code: (InputData) -> StateAction) {
    stateMachine.action(id) { runCatching { code(it) }.mapError { it.message!! }}
  }

  fun menu(code: (ButtonStateBuilder).(InputData) -> Unit) {
    stateMachine.action(id) { input -> runCatching {
      val builder = ButtonStateBuilder().apply {
        this.code(input)
      }
      ButtonsAction(
        text = if (builder.markdown.isNotBlank()) TextMessage(builder.markdown, TextMarkup.MARKDOWN) else TextMessage(builder.text),
        buttons = builder.buttonList
      )
    }.mapError { it.message!! }}
  }

  override fun toString(): String {
    return "State(id='$id', requiredNode=$requiredNode, isSubset=$isSubset, isIgnored=$isIgnored)"
  }
}

class ButtonStateBuilder(var text: String = "", var markdown: String = "", var buttonList: List<Pair<String, ButtonBuilder>> = emptyList()) {
  fun buttons(vararg buttons: Pair<String, String>) {
    buttonList = buttons.map { (label, outState) ->
      outState to ButtonBuilder(label = {label})
    }
  }
}

fun identityOutput(input: InputData) = OutputData(input.contextJson)

data class ButtonBuilder(val label: (InputData)->String, val output: (InputData)->OutputData = ::identityOutput)
data class OutputUi(
  val showButtons: (TextMessage, ButtonTransition)->Unit
)
enum class TextMarkup {
  PLAIN, MARKDOWN
}
data class TextMessage(val text: String, val markup: TextMarkup = TextMarkup.PLAIN)
class ButtonTransition(
  val buttons: List<Pair<String, ButtonBuilder>>,
  val columnCount: Int = 1,
  val inplaceUpdate: Boolean = true
)

fun State.trigger(code: ObjectNode.()->Unit) {
  this.required(objectNode {code(this)})
}

interface StateAction {
  val text: TextMessage
  val buttonTransition: ButtonTransition
}

class ButtonsAction(
  override val text: TextMessage,
  val buttons: List<Pair<String, ButtonBuilder>>): StateAction {

  override val buttonTransition: ButtonTransition
    get() = ButtonTransition(buttons)
}

class SimpleAction(
  text: String, returnState: String, input: InputData, code: (InputData)->Unit): StateAction {
  override val text = TextMessage(text)

  override val buttonTransition = ButtonTransition(listOf(
    returnState to ButtonBuilder({"<< Назад"})
  ))

  init {
    code(input)
  }
}

class BotStateMachine {
  private val states = mutableMapOf<String, State>()
  //private val transitions = mutableMapOf<String, (InputData)->Result<ButtonTransition, String>>()
  private val actions = mutableMapOf<String, (InputData)->Result<StateAction, String>>()
  fun getState(id: String) = states[id]

  fun state(id: String, code: State.()->Unit) {
    registerState(State(id, this).apply(code))
  }

  fun state(id: Int, code: State.()->Unit) {
    State(id.toString(), this).apply {
      registerState(this.apply(code))
      required("#", id)
    }
  }

  fun registerState(state: State) {
    states[state.id] = state
  }

  fun action(fromStateId: String, action: (InputData)->Result<StateAction, String>) {
    actions[fromStateId] = action
  }

  fun handle(input: InputData, outputUi: OutputUi): Result<State, String> {
    val state = states.entries.firstOrNull { it.value.matches(input) }?.value ?: return Err("The input doesn't match any state")
    LOG.debug("Entered state {}", state)
    val stateAction = actions[state.id] ?: return Err("No action is registered for state ${state.id}")
    return stateAction(input).map {
      outputUi.showButtons(it.text, it.buttonTransition)
      state
    }.onFailure { it }
  }
}


fun objectNode(prototype: ObjectNode = jacksonObjectMapper().createObjectNode(),
               builder: ObjectNode.() -> Unit) = prototype.deepCopy().apply(builder)

private val LOG = LoggerFactory.getLogger("Bot.State")