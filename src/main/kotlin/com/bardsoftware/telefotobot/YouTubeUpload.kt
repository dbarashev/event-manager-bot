package com.bardsoftware.telefotobot

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import java.nio.file.Path

object YouTubeUploader {
    // ======================
    // Credentials constants
    // Replace placeholder values with your actual credentials.
    // For security, consider overriding via environment variables in production.
    // ======================
    const val APPLICATION_NAME: String = "Vargan Film Upload Bot"
    const val CLIENT_ID: String = "1034404006152-41ldjl56tee300agpd38p5rk3i7o39tv.apps.googleusercontent.com"
    const val CLIENT_SECRET: String = ""
    const val REFRESH_TOKEN: String = ""
    // 4/0ATX87lNw1SVTXCMzz3wAQsuw3AOWaT7GMDDZ4Lk2e-TFkal-iI-_Z7aCg9m-EvkrbPT7eg
    // 4/0ATX87lO3UoofsySNYx6manUes_EGjSF0Pl-BlpfaRUgY4RMWyI5Wdigrl-xRPGAKwZMUsQ

    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val HTTP_TRANSPORT by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private fun buildRequestInitializer(): HttpRequestInitializer {
        val clientId = System.getenv("YOUTUBE_CLIENT_ID") ?: CLIENT_ID
        val clientSecret = System.getenv("YOUTUBE_CLIENT_SECRET") ?: CLIENT_SECRET
        val refreshToken = System.getenv("YOUTUBE_REFRESH_TOKEN") ?: REFRESH_TOKEN

        val userCredentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(refreshToken)
            .build()
        return HttpCredentialsAdapter(userCredentials)
    }

    private fun buildService(requestInitializer: HttpRequestInitializer): YouTube {
        return YouTube.Builder(HTTP_TRANSPORT, JSON_FACTORY, requestInitializer)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * Uploads a video file to the authorized YouTube channel.
     *
     * @param filePath Path to the video file on local filesystem
     * @param title Video title
     * @param description Video description
     * @param tags Optional list of tags
     * @param privacy One of: "public", "unlisted", "private"
     * @return The uploaded video's ID
     */
    @JvmStatic
    fun uploadVideo(
        filePath: Path,
        title: String,
        description: String,
        tags: List<String> = emptyList(),
        privacy: String = "unlisted"
    ): String {
        val requestInitializer = buildRequestInitializer()
        val youtube = buildService(requestInitializer)

        val snippet = VideoSnippet().apply {
            this.title = title
            this.description = description
            if (tags.isNotEmpty()) {
                this.tags = tags
            }
        }

        val status = VideoStatus().apply {
            this.privacyStatus = privacy
        }

        val video = Video().apply {
            this.snippet = snippet
            this.status = status
        }

        val mediaContent = FileContent("video/*", filePath.toFile())

        val insert = youtube.videos().insert(listOf("snippet", "status"), video, mediaContent)

        val uploaded = insert.execute()
        return uploaded.id
    }
}

fun main() {
    YouTubeUploader.uploadVideo(Path.of("test.mp4"), "Test video", "Test video description")
}
