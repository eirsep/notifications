/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications.resthandler

import org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT
import org.opensearch.commons.notifications.NotificationConstants.THREAD_CONTEXT_TAG
import org.opensearch.commons.notifications.action.NotificationsActions
import org.opensearch.commons.notifications.action.SendNotificationRequest
import org.opensearch.commons.utils.contentParserNextToken
import org.opensearch.notifications.NotificationPlugin.Companion.PLUGIN_BASE_URI
import org.opensearch.rest.BaseRestHandler.RestChannelConsumer
import org.opensearch.rest.RestHandler.Route
import org.opensearch.rest.RestRequest
import org.opensearch.rest.RestRequest.Method.POST
import org.opensearch.transport.client.node.NodeClient

/**
 * Rest handler for sending a message to existing notification channels.
 */
internal class SendNotificationRestHandler : PluginBaseHandler() {
    companion object {
        /**
         * Base URL for this handler
         */
        private const val REQUEST_URL = "$PLUGIN_BASE_URI/feature/send"
    }

    /**
     * {@inheritDoc}
     */
    override fun getName(): String {
        return "notifications_send"
    }

    /**
     * {@inheritDoc}
     */
    override fun routes(): List<Route> {
        return listOf(
            /**
             * Send a message to notification channels
             * Request URL: POST [REQUEST_URL]
             * Request body: Ref [org.opensearch.commons.notifications.action.SendNotificationRequest]
             * Response body: [org.opensearch.commons.notifications.action.SendNotificationResponse]
             */
            Route(POST, REQUEST_URL)
        )
    }

    /**
     * {@inheritDoc}
     */
    override fun responseParams(): Set<String> {
        return setOf()
    }

    /**
     * {@inheritDoc}
     */
    override fun executeRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        val parsed = SendNotificationRequest.parse(request.contentParserNextToken())
        // The send action authorizes channels against the user in this field, so it is taken only from the
        // authenticated caller, never from the request body.
        require(parsed.threadContext == null) { "$THREAD_CONTEXT_TAG must not be set in the request body" }
        val userInfo: String? =
            client.threadPool().threadContext.getTransient<String>(OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT)
        val sendNotificationRequest = SendNotificationRequest(
            parsed.eventSource,
            parsed.channelMessage,
            parsed.channelIds,
            userInfo
        )
        return RestChannelConsumer {
            client.execute(
                NotificationsActions.SEND_NOTIFICATION_ACTION_TYPE,
                sendNotificationRequest,
                RestResponseToXContentListener(it)
            )
        }
    }
}
