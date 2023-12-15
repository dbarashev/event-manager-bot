package com.bardsoftware.embot

import com.bardsoftware.embot.db.tables.records.EventRecord
import com.bardsoftware.embot.db.tables.records.EventviewRecord
import com.bardsoftware.libbotanique.escapeMarkdown
import java.time.LocalDateTime

fun EventRecord.formatUncheckedLabel() = """${this.title} / ${this.start!!.toLocalDate()} ${this.start!!.toLocalTime()}"""

fun EventviewRecord.getGeoLocation() = if (primaryLat != null && primaryLat != null) LatLon(primaryLat!!, primaryLon!!) else null

fun EventviewRecord.buttonLabel() = "$title " + when {
  isArchived == true && start!!.isAfter(LocalDateTime.now()) -> "[не опубликованное]"
  isArchived == true && start!!.isBefore(LocalDateTime.now()) -> "[архивное]"
  else -> "[опубликованное]"
}

fun EventviewRecord.formatDescription(registeredParticipantsMdwn: String, isOrg: Boolean) =
  """*${if (isOrg) this.buttonLabel().escapeMarkdown() else title!!.escapeMarkdown()}*
    |${seriesTitle?.escapeMarkdown() ?: ""}
    | $hline
    | *Организаторы*\: ${organizerTitle?.escapeMarkdown() ?: ""}
    | *Дата*\: ${start!!.toLocalDate().toString().escapeMarkdown()}
    | *Время*\: ${start!!.toLocalTime().toString().escapeMarkdown()}
    | *Адрес*\: ${primaryAddress ?: "\\-"}
    | ${getGeoLocation()?.let {"*Геолокация*\\: ${it.toString().escapeMarkdown()} [Google](${it.asGoogleLink().escapeMarkdown()})"}}
    | *Max\. участников*\: ${participantLimit?.toString() ?: "\\-"}
    | ${"—".repeat(20)}
    | 
    | *Зарегистрированы*\: 
    | ${registeredParticipantsMdwn}
  """.trimMargin() +
      if (isOrg) {
        """
          |
          | $hline
          | *Ссылка для регистрации*\: [${this.registrationLink().escapeMarkdown()}]
        """.trimMargin()
      } else ""

fun EventviewRecord.registrationLink() = """https://t.me/${System.getenv("TG_BOT_USERNAME")}?start=${this.id}"""

private val hline = "—".repeat(20)