/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.integtest.send

import com.google.gson.JsonArray
import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.opensearch.core.rest.RestStatus
import org.opensearch.integtest.PluginRestTestCase
import org.opensearch.notifications.NotificationPlugin.Companion.PLUGIN_BASE_URI
import org.opensearch.rest.RestRequest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

internal class SendNotificationRestHandlerIT : PluginRestTestCase() {

    private fun createWebhookConfig(path: String): String {
        val url = "http://${server.address.hostString}:${server.address.port}$path"
        val createRequestJsonString = """
        {
            "config":{
                "name":"send api webhook",
                "description":"webhook for the send api",
                "config_type":"webhook",
                "is_enabled":true,
                "webhook":{
                    "url":"$url",
                    "header_params": {
                       "Content-type": "text/plain"
                    }
                }
            }
        }
        """.trimIndent()
        val configId = createConfigWithRequestJsonString(createRequestJsonString)
        Assert.assertNotNull(configId)
        Thread.sleep(1000)
        return configId
    }

    private fun sendRequestBody(channelIds: List<String>, extraFields: String = ""): String {
        val ids = channelIds.joinToString(",") { "\"$it\"" }
        return """
        {
            "event_source":{
                "title":"Weekly report",
                "reference_id":"report-1",
                "severity":"info",
                "tags":[]
            },
            "channel_message":{
                "text_description":"Your report is ready"
            },
            "channel_id_list":[$ids]$extraFields
        }
        """.trimIndent()
    }

    fun `test send delivers the caller supplied message to every channel`() {
        val firstConfigId = createWebhookConfig("/first")
        val secondConfigId = createWebhookConfig("/second")

        val sendResponse = executeRequest(
            RestRequest.Method.POST.name,
            "$PLUGIN_BASE_URI/feature/send",
            sendRequestBody(listOf(firstConfigId, secondConfigId)),
            RestStatus.OK.status
        )

        val statusList = sendResponse.get("status_list") as JsonArray
        Assert.assertEquals(2, statusList.size())
        statusList.forEach {
            val deliveryStatus = it.asJsonObject.get("delivery_status").asJsonObject
            Assert.assertEquals("200", deliveryStatus.get("status_code").asString)
        }
        Assert.assertTrue(receivedBodies.any { it.contains("Your report is ready") })
    }

    fun `test send rejects a user context in the request body`() {
        val configId = createWebhookConfig("/first")

        executeRequest(
            RestRequest.Method.POST.name,
            "$PLUGIN_BASE_URI/feature/send",
            sendRequestBody(listOf(configId), ",\"context\":\"admin||all_access\""),
            RestStatus.BAD_REQUEST.status
        )
    }

    fun `test send reports a missing channel as not found`() {
        val sendResponse = executeRequest(
            RestRequest.Method.POST.name,
            "$PLUGIN_BASE_URI/feature/send",
            sendRequestBody(listOf("does-not-exist")),
            RestStatus.NOT_FOUND.status
        )

        Assert.assertNotNull(sendResponse.get("error").asJsonObject.get("reason").asString)
    }

    fun `test send requires a channel list`() {
        val body = """
        {
            "event_source":{"title":"Weekly report","reference_id":"report-1","severity":"info","tags":[]},
            "channel_message":{"text_description":"Your report is ready"}
        }
        """.trimIndent()

        executeRequest(
            RestRequest.Method.POST.name,
            "$PLUGIN_BASE_URI/feature/send",
            body,
            RestStatus.BAD_REQUEST.status
        )
    }

    companion object {
        private lateinit var server: HttpServer
        private val receivedBodies = CopyOnWriteArrayList<String>()

        @JvmStatic
        @BeforeClass
        fun setupWebhook() {
            server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            listOf("/first", "/second").forEach { path ->
                server.createContext(path) { exchange ->
                    receivedBodies.add(exchange.requestBody.bufferedReader().readText())
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
            }
            server.start()
        }

        @JvmStatic
        @AfterClass
        fun stopMockServer() {
            server.stop(1)
        }
    }
}
