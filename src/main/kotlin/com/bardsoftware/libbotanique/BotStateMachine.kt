package com.bardsoftware.libbotanique

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.michaelbull.result.*
import org.slf4j.LoggerFactory
import kotlin.collections.map
import kotlin.collections.toMutableSet


class State(val id: String, internal val stateMachine: BotStateMachine) {
  private val requiredNode = jacksonObjectMapper().createObjectNode()
  private val requiredPredicates = mutableMapOf<String, (JsonNode)->Boolean>()

  val stateJson get() = requiredNode

  var isSubset: Boolean = false
  var isIgnored: Boolean = false
  var commands = emptySet<String>()
  internal var hasDialog = false


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

  private val matchers = mutableListOf<(InputEnvelope)->Boolean>().also {
    it.add { input ->
      val requiredSet = requiredNode.fields().asSequence().toSet()
      val nodeSet = input.stateJson.fields().asSequence().toSet()
      if (!nodeSet.containsAll(requiredSet)) {
        return@add false
      }
      if (isSubset) {
        return@add true
      }
      return@add nodeSet.map { it.key }.toMutableSet().let {
        it.removeAll(requiredSet.map { it.key })
        it.isEmpty()
      }
    }
  }

  fun matches(input: InputEnvelope): Boolean {
    return matchers.any { it(input) }
  }

  fun action(code: (InputEnvelope) -> StateAction) {
    stateMachine.action(id) { runCatching { code(it) }.mapError { it.message!! }}
  }

  fun command(vararg commands: String) {
    this.commands = commands.toSet()
    this.matchers.add {
      it.contents is InputCommand && it.command in commands
    }
  }

  fun menu(code: (ButtonStateBuilder).(InputEnvelope) -> Unit) {
    stateMachine.action(id) { input -> runCatching {
      val builder = ButtonStateBuilder().apply {
        this.code(input)
      }
       ButtonsAction(
        text = if (builder.markdown.isNotBlank()) TextMessage(builder.markdown, TextMarkup.MARKDOWN) else TextMessage(builder.text),
        buttons = builder.buttonList
      )
    }.mapError {
      LOG.error("Failre when building a menu", it)
      it.message!!
    }}
  }

  fun dialog(dialogBuilder: Dialog.()->Unit) {
    val dlg = Dialog(this).apply(dialogBuilder)
    matchers.add {
      dlg.matches(it)
    }
    action(dlg::process)
    hasDialog = true
  }

  override fun toString(): String {
    return "State(id='$id', requiredNode=$requiredNode, isSubset=$isSubset, isIgnored=$isIgnored)"
  }
}

class ButtonStateBuilder(var text: String = "", var markdown: String = "", var buttonList: List<Pair<String, OutputButton>> = emptyList()) {
  fun buttons(vararg buttons: Pair<String, String>) {
    buttonList = buttons.map { (label, outState) ->
      outState to OutputButton(outState, label = label)
    }
  }
}

fun identityOutput(input: InputEnvelope) = OutputData(input.stateJson)

data class OutputButton(val targetState: String, val label: String, val payload: String? = null, val output: (InputEnvelope)->OutputData = ::identityOutput)

fun OutputButton.callbackJson(envelope: InputEnvelope, sm: BotStateMachine): ObjectNode =
  sm.getState(targetState)?.stateJson?.let {
    it.deepCopy().apply { setContext(payload?.asJson() ?: output(envelope).contextJson) }
  } ?: emptyNode()


/**
 * This class groups functions that render the bot output elements.
 */
data class OutputUi(
    /** Shows a text message with a list of callback buttons. */
  val showButtons: (TextMessage, ButtonBlock)->Unit,
)
enum class TextMarkup {
  PLAIN, MARKDOWN
}

data class TextMessage(val text: String, val markup: TextMarkup = TextMarkup.PLAIN)
class ButtonBlock(
  val buttons: List<OutputButton>,
  val columnCount: Int = 1,
  val inplaceUpdate: Boolean = true
)

fun stateMachine(sessionProvider: UserSessionProvider, code: (BotStateMachine).()->Unit): BotStateMachine = BotStateMachine(sessionProvider).apply(code)

fun State.trigger(code: ObjectNode.()->Unit) {
  this.required(objectNode {code(this)})
}

interface StateAction {
  val text: TextMessage
  val buttonBlock: ButtonBlock
}

class ButtonsAction(
  override val text: TextMessage,
  val buttons: List<Pair<String, OutputButton>>): StateAction {

  override val buttonBlock: ButtonBlock
    get() = ButtonBlock(buttons.map { it.second })
}

class SimpleAction(
  text: String, returnState: String, input: InputEnvelope, code: (InputEnvelope)->Unit): StateAction {
  override val text = TextMessage(text)

  override val buttonBlock = ButtonBlock(listOf(
    OutputButton(returnState,"<< Назад")
  ))

  init {
    code(input)
  }
}

class BotStateMachine(val sessionProvider: UserSessionProvider) {
  private val states = mutableMapOf<String, State>()
  //private val transitions = mutableMapOf<String, (InputData)->Result<ButtonTransition, String>>()
  private val actions = mutableMapOf<String, (InputEnvelope)->Result<StateAction, String>>()
  fun getState(id: String) = states[id]

  fun landing(code: State.()->Unit) {
    State(landingStateId, this).apply {
      registerState(this.apply(code))
    }
  }

  val landingStateId = "START"

  fun state(id: String, shortId: String = id, code: State.()->Unit) {
    State(id,  this).apply {
      registerState(this.apply(code))
      if (shortId.isNotBlank()) required("#", shortId)
    }
  }

  fun state(id: Int, code: State.()->Unit) {
    state(id.toString(), code = code)
  }

  fun registerState(state: State) {
    states[state.id] = state
  }

  fun action(fromStateId: String, action: (InputEnvelope)->Result<StateAction, String>) {
    actions[fromStateId] = action
  }

  fun handle(input: InputEnvelope, outputUi: OutputUi): Result<State, String> {
    var state = states.entries.firstOrNull { it.value.matches(input) }?.value ?: return Err("The input doesn't match any state")
    if (input.contents !is InputTransition) {
      val stateContext = sessionProvider(input.user.id.toLong()).load(state.id)?.asJson() ?: jacksonObjectMapper().createObjectNode()
      state = stateContext.getRedirectStateId()?.let { states[it] } ?: state
    }

    return handle(state, input, outputUi)
  }

  fun handle(state: State, input: InputEnvelope, outputUi: OutputUi): Result<State, String> {
    if (state.hasDialog) {
      val landingContext = sessionProvider(input.user.id.toLong()).load(landingStateId)?.asJson()
        ?: jacksonObjectMapper().createObjectNode()
      landingContext.setRedirectStateId(state.id)
      sessionProvider(input.user.id.toLong()).save(landingStateId, landingContext.toString())
    }
    LOG.debug("Entered state {}", state)
    val stateAction = actions[state.id] ?: return Err("No action is registered for state ${state.id}")
    return stateAction(input).map {
      outputUi.showButtons(it.text, it.buttonBlock)
      state
    }.onFailure { it }
  }
}

fun ObjectNode.getRedirectStateId() = this.get("=>")?.asText()
fun ObjectNode.setRedirectStateId(stateId: String) = this.put("=>", stateId)

fun objectNode(prototype: ObjectNode? = null,
               builder: ObjectNode.() -> Unit) = (prototype ?: jacksonObjectMapper().createObjectNode()).deepCopy().apply(builder)

private val LOG = LoggerFactory.getLogger("Bot.State")