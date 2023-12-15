package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventteamregistrationviewRecord
import com.bardsoftware.embot.db.tables.references.EVENTTEAMREGISTRATIONVIEW
import com.bardsoftware.embot.db.tables.references.EVENTVIEW
import com.bardsoftware.embot.db.tables.references.ORGANIZER
import com.bardsoftware.libbotanique.getMessageSender
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import java.util.concurrent.Executors


val sheetsService by lazy {
  val credentials = GoogleCredentials.getApplicationDefault()
  val transport = GoogleNetHttpTransport.newTrustedTransport();
  Sheets.Builder(transport, GsonFactory.getDefaultInstance(), HttpCredentialsAdapter(credentials)).build()
}

fun notifyRegistration(eventId: Int, participantIds: List<Int>) {
  googleSheetScope.launch {
    val allParticipants: List<EventteamregistrationviewRecord> = db {
      selectFrom(EVENTTEAMREGISTRATIONVIEW).where(EVENTTEAMREGISTRATIONVIEW.ID.eq(eventId)).toList()
    }
    db {
      select(ORGANIZER.GOOGLE_SHEET_ID, EVENTVIEW.NOTIFICATION_CHAT_ID, EVENTVIEW.TITLE)
        .from(EVENTVIEW.join(ORGANIZER).on(EVENTVIEW.ORGANIZER_ID.eq(ORGANIZER.ID)))
        .where(EVENTVIEW.ID.eq(eventId))
        .fetchOne()
    }?.let { orgRecord ->

      orgRecord.component1()?.let { googleSheetId ->
        val sheetName = orgRecord.component3() ?: "--"

        try {
          val spreadsheet = sheetsService.spreadsheets().get(googleSheetId).execute()
          spreadsheet.sheets.find { it.properties.title == sheetName } ?: run {
            val requests = listOf(
              Request().setAddSheet(
                AddSheetRequest().setProperties(
                  SheetProperties().setTitle(sheetName)
                )
              )
            )
            sheetsService.spreadsheets()
              .batchUpdate(googleSheetId, BatchUpdateSpreadsheetRequest().setRequests(requests)).execute()
          }
          val values = allParticipants.map {
            listOf(it.participantName, it.participantAge, it.registrantName, it.registrantPhone, it.registrantUsername)
          }.toList()
          val body: ValueRange = ValueRange().setValues(values)
          sheetsService.spreadsheets().values()
            .update(googleSheetId, "'$sheetName'!A1", body)
            .setValueInputOption("RAW")
            .execute()
        } catch (e: Exception) {
          e.printStackTrace()
          println(e.message)
        }
      }

      orgRecord.component2()?.let { notificationChatId ->
        LOG_NOTIFICATION.debug("Sending notification to {}", notificationChatId)
        allParticipants.filter { participantIds.contains(it.participantId) }.forEach {
          getMessageSender().send(SendMessage().apply {
            chatId = notificationChatId
            text = "Зарегистрирован участник ${it.participantName}, ${it.participantAge} на событие ${it.title}"
          })
        }
      }
    }
  }
}

private val LOG_SYNC = LoggerFactory.getLogger("Bot.Sync")
private val LOG_NOTIFICATION = LoggerFactory.getLogger("Bot.Notification")
private val googleSheetScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())