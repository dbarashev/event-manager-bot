package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.embot.db.tables.records.ParticipantRecord
import com.bardsoftware.embot.db.tables.references.EVENTREGISTRATION
import com.bardsoftware.embot.db.tables.references.EVENTSERIESSUBSCRIPTION
import com.bardsoftware.libbotanique.BtnData
import com.bardsoftware.libbotanique.ChainBuilder
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

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
  val sendRegistered: (participant: ParticipantRecord) -> Unit,
  val sendRedirectToSettings: (participant: ParticipantRecord) -> Unit,
  val close: (payload: ObjectNode) -> Unit
)

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
          outputApi.close(jacksonObjectMapper().createObjectNode().apply {
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
    payload.getParticipantId()?.let {
      candidateIds.add(it)
      storageApi.setCandidateIdList(candidateIds)
      payload.clearParticipantId()
    }
    val candidates = storageApi.getAllParticipants(candidateIds)
    // -------------------------------------------------------------------------
    val remainingMembers = allMembers.filter { member  -> candidates.find { it.id == member.id } == null }
    val buttons =
      listOf(
        BtnData("Новый участник...", callbackData = json(payload) {
          setSection(CbSection.TEAM)
          setCommand(CbTeamCommand.ADD_DIALOG)
          set<ObjectNode>("esc", payload)
        })
      ) + remainingMembers.map { member ->
        BtnData("${member.displayName}, ${member.age}", callbackData = json(payload) {
          put("id", member.id)
        })
      }.toList() + listOf(
        BtnData("Себя", callbackData = json(payload) {
          put("id", registrant.id)
        })
      ) + listOf(BtnData("OK >>", callbackData = json(payload) {
        setConfirmation()
      }))
    outputApi.sendWhomAdd(candidates, buttons)
      // -------------------------------------------------------------------------
  }
}

fun createOutputApiProd(tg: ChainBuilder, inputPayload: ObjectNode): RegistrationFlowOutputApi =
  RegistrationFlowOutputApi(
    close = {payload ->
      tg.reply("Всех зарегистрировали!", buttons = listOf(
        BtnData("<< Назад", callbackData = json {
          setSection(CbSection.EVENTS)
          setCommand(CbEventCommand.LIST)
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
    sendRegistered = {participant ->
      // -------------------------------------------------------------------------
      tg.reply("Вы зарегистрированы!",
        buttons = participant.getEventButtons(inputPayload),
        maxCols = 1, isInplaceUpdate = true)
      // -------------------------------------------------------------------------
    },
    sendWhomAdd = {candidates, buttons ->
      val names = candidates.joinToString(separator = ",") {
        it.displayName!!
      }
      val msg = if (names.isNotBlank()) {
        """Будут зарегистрированы: $names
          |
          |Чтобы завершить регистрацию, нажмите кнопку ОК.
        """.trimMargin()
      } else {
        """
          Выберите одного или нескольких человек для регистрации. 
          Выбирайте только тех, кто действительно будет участвовать. 
          Если нужно, создайте нового участника, например ребенка, кнопкой "Новый участник..."
        """.trimIndent()
      }
      tg.reply(
        """$msg
        |
        |Кого добавить?""".trimMargin(),
        buttons = buttons,
        maxCols = 1,
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
          setCommand(CbEventCommand.LIST)
        })
      ), isInplaceUpdate = true)
    }
  )
fun createStorageApiProd(tg: ChainBuilder): RegistrationFlowStorageApi =
  RegistrationFlowStorageApi(
    getCandidateIdList = {
      tg.userSession.state?.asJson()?.get("participant_ids")?.let { idList ->
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
      tg.userSession.reset()
    }
  )
private fun ObjectNode.hasConfirmation() = this["y"]?.asBoolean() ?: false
private fun ObjectNode.setConfirmation() = this.put("y", true)

private fun ObjectNode.getParticipantId() = this["id"]?.asInt()
private fun ObjectNode.setParticipantId(id: Int) = this.put("id", id)
private fun ObjectNode.clearParticipantId() = this.remove("id")
