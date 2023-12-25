package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.EVENTREGISTRATION
import com.bardsoftware.embot.db.tables.references.EVENTSERIESSUBSCRIPTION
import com.bardsoftware.libbotanique.*
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

data class RegistrationFlowStorageApi(
  val getCandidateIdList: () -> List<Int>?,
  val setCandidateIdList: (List<Int>) -> Unit,
  val getAllParticipants: (List<Int>) -> List<ParticipantRecord>,
  val getSubscriptionId: (event: EventviewRecord, participant: ParticipantRecord) -> Int?,
  val insertParticipants: (ids: List<Int>, event: EventviewRecord, subscriptionId: Int) -> Unit,
  val getTeam: (participant: ParticipantRecord) -> List<ParticipantRecord>,
  val close: () -> Unit
)

data class RegistrationFlowOutputApi(
  val sendWhomAdd: (candidates: List<ParticipantRecord>, btns: List<BtnData>) -> Unit,
  val sendRedirectToSettings: (participant: ParticipantRecord) -> Unit,
  val close: (payload: ObjectNode) -> Unit
)

class EventRegisterAction
class RegistrationFlow(
  private val storageApi: RegistrationFlowStorageApi,
  private val outputApi: RegistrationFlowOutputApi) {

  fun process(event: EventviewRecord, registrant: ParticipantRecord, payload: ObjectNode) {
    if (payload.hasConfirmation()) {
      storageApi.getCandidateIdList()?.let {participantIds ->
        val subscriptionId = storageApi.getSubscriptionId(event, registrant) ?: return
        storageApi.insertParticipants(participantIds, event, subscriptionId)
        storageApi.close()
        if (registrant.hasMissingSettings()) {
          outputApi.sendRedirectToSettings(registrant)
        } else {
          outputApi.close(objectNode {
            setEventId(event.id!!)
            putArray("participant_ids").let { arrayNode ->
              participantIds.forEach(arrayNode::add)
            }
          })
        }
      }
      return
    }

    val allMembers = storageApi.getTeam(registrant)

    val candidateIds = storageApi.getCandidateIdList()?.toMutableList() ?: mutableListOf()
    payload.getTeamMemberId()?.let {
      candidateIds.add(it)
      storageApi.setCandidateIdList(candidateIds)
      payload.clearParticipantId()
    }
    val candidates = storageApi.getAllParticipants(candidateIds)
    // -------------------------------------------------------------------------
    val remainingMembers = allMembers.filter { member  -> candidates.find { it.id == member.id } == null }
    val escapeFromMemberAdd = objectNode {
      setSection(CbSection.EVENTS)
      setCommand(CbEventCommand.REGISTER)
      setEventId(event.id!!)
    }
    val buttons =
      remainingMembers.map { member ->
        BtnData("${member.displayName}, ${member.age}", callbackData = json(payload) {
          setTeamMemberId(member.id!!)
        })
      }.toList() + listOf(
        BtnData("Себя", callbackData = json(payload) {
          setTeamMemberId(registrant.id!!)
        }),
        BtnData("Другого человека...", callbackData = json {
          setSection(CbSection.DIALOG)
          setDialogId(CbTeamCommand.ADD_DIALOG.id)
          set<ObjectNode>("esc", escapeFromMemberAdd)
        }),
        BtnData("OK \u2611", callbackData = json(payload) {
          setConfirmation()
        }),
        BtnData("\uD83D\uDD19 Назад", callbackData = json{
          setSection(CbSection.EVENTS)
          setCommand(CbEventCommand.LANDING)
        })
      )
    outputApi.sendWhomAdd(candidates, buttons)
      // -------------------------------------------------------------------------
  }
}

fun createOutputApiProd(tg: ChainBuilder, inputPayload: ObjectNode): RegistrationFlowOutputApi =
  RegistrationFlowOutputApi(
    close = {payload ->
      tg.reply("Всех зарегистрировали!", buttons = listOf(
        BtnData("<< Назад", callbackData = json {
          put("#", EMBotState.PARTICIPANT_SHOW_EVENT.id)
          set<ObjectNode>("_", objectNode {
            inputPayload.getEventId()?.let(this::setEventId)
          })
        }),
      ), isInplaceUpdate = true)
      payload.getEventId()?.let {eventId ->
        notifyRegistration(eventId, (payload.get("participant_ids") as? ArrayNode)?.mapNotNull {
          if (it.isInt) {
            it.asInt()
          } else null
        }?.toList() ?: emptyList())
      }
    },
    sendWhomAdd = {candidates, buttons ->
      val names = candidates.joinToString(separator = ", ") {
        it.displayName!!
      }
      val msg = if (names.isNotBlank()) {
        """
          |*Нажмите кнопку ОК* чтобы зарегистрировать этот список\: 
          |${"\\-".repeat(20)}
          |_${names.escapeMarkdown()}_
          |${"\\-".repeat(20)}
          |
          |Добавляйте участников в список, используя кнопки с их именами\.
        """.trimMargin()
      } else {
        """
          *Список для регистрации*
          ${"\\-".repeat(20)}
          
          \- Составьте список людей для регистрации, используя кнопки с именами\. Вносите в список только тех, кто действительно будет участвовать\. 
          \- Если нужно, создайте нового участника, например ребенка, кнопкой "Другого человека\.\.\."
        """.trimIndent()
      }
      tg.reply(
        """$msg
        |
        |Кого добавить в список?""".trimMargin(),
        buttons = buttons,
        maxCols = 1,
        isMarkdown = true,
        isInplaceUpdate = true
      )
    },
    sendRedirectToSettings = { participant ->
      tg.reply("""Нам нужна ваша контактная информация. Нажмите кнопку "Настройки" чтобы её ввести.""", buttons = listOf(
        BtnData("Настройки >>", callbackData = json {
          setSection(CbSection.SETTINGS)
        }),
        BtnData("<< К событиям", callbackData = json {
          setSection(CbSection.EVENTS)
          setCommand(CbEventCommand.LANDING)
        })
      ), isInplaceUpdate = true)
    }
  )
fun createStorageApiProd(tg: ChainBuilder): RegistrationFlowStorageApi =
  RegistrationFlowStorageApi(
    getCandidateIdList = {
      tg.userSession.load(CbEventCommand.REGISTER.id)?.asJson()?.get("participant_ids")?.let { idList ->
        if (idList.isArray && idList is ArrayNode) {
          idList.mapNotNull {
            if (it.isInt) {
              it.asInt()
            } else null
          }
        } else null
      }
    },
    setCandidateIdList = { candidateIds ->
      tg.userSession.save(CbEventCommand.REGISTER.id, json {
        putArray("participant_ids").let { arrayNode ->
          candidateIds.toSet().forEach(arrayNode::add)
        }
      })
    },
    getAllParticipants = {ids -> ids.mapNotNull { findParticipant(it) }},
    getSubscriptionId = {
      event, registrant: ParticipantRecord ->

      db {
        selectFrom(EVENTSERIESSUBSCRIPTION)
          .where(
            EVENTSERIESSUBSCRIPTION.SERIES_ID.eq(event.seriesId)
              .and(EVENTSERIESSUBSCRIPTION.PARTICIPANT_ID.eq(registrant.id))
          ).fetchOne()?.id
      }
    },
    insertParticipants = { ids: List<Int>, event, subscriptionId: Int ->
      txn {
        ids.forEach { participantId ->
          insertInto(EVENTREGISTRATION)
            .columns(EVENTREGISTRATION.PARTICIPANT_ID, EVENTREGISTRATION.EVENT_ID, EVENTREGISTRATION.SUBSCRIPTION_ID)
            .values(participantId, event.id, subscriptionId)
            .onConflictDoNothing()
            .execute()
        }
      }
    },
    getTeam = { participant -> participant.teamMembers() },
    close = {
      tg.userSession.reset(CbEventCommand.REGISTER.id)
    }
  )
private fun ObjectNode.hasConfirmation() = this["y"]?.asBoolean() ?: false
private fun ObjectNode.setConfirmation() = this.put("y", true)

private fun ObjectNode.clearParticipantId() = this.remove("id")
