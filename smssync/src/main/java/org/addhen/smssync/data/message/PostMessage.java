/*
 * Copyright (c) 2010 - 2015 Ushahidi Inc
 * All rights reserved
 * Contact: team@ushahidi.com
 * Website: http://www.ushahidi.com
 * GNU Lesser General Public License Usage
 * This file may be used under the terms of the GNU Lesser
 * General Public License version 3 as published by the Free Software
 * Foundation and appearing in the file LICENSE.LGPL included in the
 * packaging of this file. Please review the following information to
 * ensure the GNU Lesser General Public License version 3 requirements
 * will be met: http://www.gnu.org/licenses/lgpl.html.
 *
 * If you have questions regarding the use of this file, please contact
 * Ushahidi developers at team@ushahidi.com.
 */

package org.addhen.smssync.data.message;

import com.google.gson.Gson;

import org.addhen.smssync.R;
import org.addhen.smssync.data.PrefsFactory;
import org.addhen.smssync.data.cache.FileManager;
import org.addhen.smssync.data.entity.Filter;
import org.addhen.smssync.data.entity.Message;
import org.addhen.smssync.data.entity.MessagesUUIDSResponse;
import org.addhen.smssync.data.entity.QueuedMessages;
import org.addhen.smssync.data.entity.SmssyncResponse;
import org.addhen.smssync.data.entity.SyncUrl;
import org.addhen.smssync.data.net.MessageHttpClient;
import org.addhen.smssync.data.repository.datasource.filter.FilterDataSourceFactory;
import org.addhen.smssync.data.repository.datasource.message.MessageDataSourceFactory;
import org.addhen.smssync.data.repository.datasource.webservice.WebServiceDataSourceFactory;
import org.addhen.smssync.data.util.Logger;
import org.addhen.smssync.data.util.Utility;
import org.addhen.smssync.smslib.sms.ProcessSms;

import android.content.Context;
import android.text.TextUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Posts {@link Message} to a configured web service
 *
 * @author Ushahidi Team <team@ushahidi.com>
 */
@Singleton
public class PostMessage extends ProcessMessage {

    private MessageHttpClient mMessageHttpClient;

    private ProcessMessageResult mProcessMessageResult;

    private String mErrorMessage;

    @Inject
    public PostMessage(Context context, PrefsFactory prefsFactory,
            MessageHttpClient messageHttpClient,
            MessageDataSourceFactory messageDataSourceFactory,
            WebServiceDataSourceFactory webServiceDataSourceFactory,
            FilterDataSourceFactory filterDataSourceFactory,
            ProcessSms processSms,
            FileManager fileManager,
            ProcessMessageResult processMessageResult) {
        super(context, prefsFactory, messageDataSourceFactory, webServiceDataSourceFactory,
                filterDataSourceFactory, processSms, fileManager);

        mMessageHttpClient = messageHttpClient;
        mProcessMessageResult = processMessageResult;
    }

    /**
     * Processes the incoming SMS to figure out how to exactly route the message. If it fails to be
     * synced online, cache it and queue it up for the scheduler to process it.
     *
     * @param message The sms to be routed
     * @return boolean
     */
    public boolean routeSms(Message message) {
        Logger.log(TAG, "routeSms uuid: " + message.toString());
        // Double check if SMSsync service is running
        if (!mPrefsFactory.serviceEnabled().get()) {
            return false;
        }

        // Send auto response from phone not server
        if (mPrefsFactory.enableReply().get()) {
            // send auto response as SMS to user's phone
            logActivities(R.string.auto_response_sent);
            Message msg = new Message();
            msg.messageBody = mPrefsFactory.reply().get();
            msg.messageFrom = message.messageFrom;
            msg.messageType = message.messageType;
            mProcessSms.sendSms(map(msg), false);
        }
        if (Utility.isConnected(mContext)) {
            List<SyncUrl> syncUrlList = mWebServiceDataSource
                    .get(SyncUrl.Status.ENABLED);
            List<Filter> filters = mFilterDataSource.getFilters();
            for (SyncUrl syncUrl : syncUrlList) {
                // Process if white-listing is enabled
                if (mPrefsFactory.enableWhitelist().get()) {
                    for (Filter filter : filters) {
                        if (filter.phoneNumber.equals(message.messageFrom)) {
                            if (postMessage(message, syncUrl)) {
                                postToSentBox(message);
                                deleteFromSmsInbox(message);
                            } else {
                                savePendingMessage(message);
                            }
                        }
                    }
                }

                // Process blacklist
                if (mPrefsFactory.enableBlacklist().get()) {
                    for (Filter filter : filters) {

                        if (filter.phoneNumber.equals(message.messageFrom)) {
                            Logger.log("message",
                                    " from:" + message.messageFrom + " filter:"
                                            + filter.phoneNumber);
                            return false;
                        } else {
                            if (postMessage(message, syncUrl)) {
                                postToSentBox(message);
                                deleteFromSmsInbox(message);
                            } else {
                                savePendingMessage(message);
                            }
                        }

                    }
                } else {
                    if (postMessage(message, syncUrl)) {
                        postToSentBox(message);
                        deleteFromSmsInbox(message);
                    } else {
                        savePendingMessage(message);
                    }
                }
            }
            return true;
        }

        // There is no internet save message
        savePendingMessage(message);
        return false;
    }

    /**
     * Sync pending messages to the configured sync URL.
     *
     * @param uuid The message uuid
     */
    public boolean syncPendingMessages(final String uuid) {
        Logger.log(TAG, "syncPendingMessages: push pending messages to the Sync URL" + uuid);
        boolean status = false;
        // check if it should sync by id
        if (!TextUtils.isEmpty(uuid)) {
            final Message message = mMessageDataSource.fetchMessageByUuid(uuid);
            List<Message> messages = new ArrayList<Message>();
            messages.add(message);
            status = postMessage(messages);
        } else {
            final List<Message> messages = mMessageDataSource.fetchMessage(Message.Type.PENDING);
            if (messages != null && messages.size() > 0) {
                for (Message message : messages) {
                    status = postMessage(messages);
                }
            }
        }

        return status;
    }

    public boolean postMessage(List<Message> messages) {
        Logger.log(TAG, "postMessages");
        List<SyncUrl> syncUrlList = mWebServiceDataSource.listWebServices();
        List<Filter> filters = mFilterDataSource.getFilters();
        for (SyncUrl syncUrl : syncUrlList) {
            // Process if white-listing is enabled
            if (mPrefsFactory.enableWhitelist().get()) {
                for (Filter filter : filters) {
                    for (Message message : messages) {
                        if (filter.phoneNumber.equals(message.messageFrom)) {
                            if (postMessage(message, syncUrl)) {
                                postToSentBox(message);
                            }
                        }
                    }
                }
            }

            if (mPrefsFactory.enableBlacklist().get()) {
                for (Filter filter : filters) {
                    for (Message msg : messages) {
                        if (!filter.phoneNumber.equals(msg.messageFrom)) {
                            Logger.log("message",
                                    " from:" + msg.messageFrom + " filter:"
                                            + filter.phoneNumber);
                            if (postMessage(msg, syncUrl)) {
                                postToSentBox(msg);
                            }
                        }
                    }
                }
            } else {
                for (Message messg : messages) {
                    if (postMessage(messg, syncUrl)) {
                        postToSentBox(messg);
                    }
                }
            }
        }
        return true;
    }


    public boolean routePendingMessage(Message message) {
        Logger.log(TAG, "postMessages");
        List<SyncUrl> syncUrlList = mWebServiceDataSource.listWebServices();
        List<Filter> filters = mFilterDataSource.getFilters();
        for (SyncUrl syncUrl : syncUrlList) {
            // Process if white-listing is enabled
            if (mPrefsFactory.enableWhitelist().get()) {
                for (Filter filter : filters) {

                    if (filter.phoneNumber.equals(message.messageFrom)) {
                        if (postMessage(message, syncUrl)) {
                            postToSentBox(message);
                        }
                    }

                }
            }

            if (mPrefsFactory.enableBlacklist().get()) {
                for (Filter filter : filters) {

                    if (!filter.phoneNumber.equals(message.messageFrom)) {
                        Logger.log("message",
                                " from:" + message.messageFrom + " filter:"
                                        + filter.phoneNumber);
                        if (postMessage(message, syncUrl)) {
                            postToSentBox(message);
                        }
                    }

                }
            } else {
                if (postMessage(message, syncUrl)) {
                    postToSentBox(message);
                }

            }
        }
        return true;
    }

    private void sendSMSWithMessageResultsAPIEnabled(SyncUrl syncUrl, List<Message> msgs) {
        QueuedMessages messagesUUIDs = new QueuedMessages();
        for (Message msg : msgs) {
            msg.messageType = Message.Type.TASK;
            messagesUUIDs.getQueuedMessages().add(msg.messageUuid);
        }

        MessagesUUIDSResponse response =
                mProcessMessageResult.sendQueuedMessagesPOSTRequest(syncUrl, messagesUUIDs);
        if (null != response && response.isSuccess() && response.hasUUIDs()) {
            for (Message msg : msgs) {
                msg.messageType = Message.Type.TASK;
                if (response.getUuids().contains(msg.messageUuid)) {
                    sendTaskSms(msg);
                    mFileManager.appendAndClose(mContext.getString(R.string.processed_task,
                            msg.messageBody));
                }
            }
        }
    }

    private void sendSMSWithMessageResultsAPIDisabled(List<Message> msgs) {
        for (Message msg : msgs) {
            msg.messageType = Message.Type.TASK;
            sendTaskSms(msg);
        }
    }

    /**
     * Send the response received from the server as SMS
     *
     * @param response The JSON string response from the server.
     */
    private void smsServerResponse(SmssyncResponse response) {
        Logger.log(TAG, "performResponseFromServer(): " + " response:"
                + response);
        if (!mPrefsFactory.enableReplyFrmServer().get()) {
            return;
        }

        if (response != null && response.getPayload() != null
                && response.getPayload().getMessages().size() > 0) {
            for (Message msg : response.getPayload().getMessages()) {
                sendTaskSms(msg);
            }
        }
    }

    private boolean postMessage(Message message, SyncUrl syncUrl) {
        // Process filter text (keyword or RegEx)
        if (!TextUtils.isEmpty(syncUrl.getKeywords())
                && syncUrl.getKeywordStatus() == SyncUrl.KeywordStatus.ENABLED) {
            List<String> keywords = new ArrayList<>(
                    Arrays.asList(syncUrl.getKeywords().split(",")));
            if (filterByKeywords(message.messageBody, keywords) || filterByRegex(
                    message.messageBody, keywords)) {
                return postToWebService(message, syncUrl);
            }
        }
        return postToWebService(message, syncUrl);
    }

    private boolean postToWebService(Message message, SyncUrl syncUrl) {
        boolean posted;
        if (message.messageType == Message.Type.PENDING) {
            Logger.log(TAG, "Process message with keyword filtering enabled " + message);
            posted = mMessageHttpClient.postSmsToWebService(syncUrl, message,
                    message.messageFrom, mPrefsFactory.uniqueId().get());
        } else {
            posted = sendTaskSms(message);
        }
        if (!posted) {
            processRetries(message);
        }
        return posted;
    }

    public void performTask() {
        if ((!mPrefsFactory.serviceEnabled().get()) || (!mPrefsFactory.enableTaskCheck().get())) {
            // Don't continue
            return;
        }
        Logger.log(TAG, "performTask(): perform a task");
        logActivities(R.string.perform_task);
        List<SyncUrl> syncUrls = mWebServiceDataSource.get(SyncUrl.Status.ENABLED);
        for (SyncUrl syncUrl : syncUrls) {
            StringBuilder uriBuilder = new StringBuilder(syncUrl.getUrl());
            final String urlSecret = syncUrl.getSecret();
            uriBuilder.append("?task=send");

            if (!TextUtils.isEmpty(urlSecret)) {
                String urlSecretEncoded = urlSecret;
                uriBuilder.append("&secret=");
                try {
                    urlSecretEncoded = URLEncoder.encode(urlSecret, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    Logger.log(TAG, e.getMessage());
                }
                uriBuilder.append(urlSecretEncoded);
            }

            mMessageHttpClient.setUrl(uriBuilder.toString());
            SmssyncResponse smssyncResponses = null;
            Gson gson = null;
            try {
                mMessageHttpClient.execute();
                gson = new Gson();
                final String response = mMessageHttpClient.getResponse().body().string();
                mFileManager.appendAndClose("HTTP Client Response: " + response);
                smssyncResponses = gson.fromJson(response, SmssyncResponse.class);
            } catch (Exception e) {
                Logger.log(TAG, "Task checking crashed " + e.getMessage() + " response: "
                        + mMessageHttpClient.getResponse());
                try {
                    mFileManager.appendAndClose(
                            "Task crashed: " + e.getMessage() + " response: " + mMessageHttpClient
                                    .getResponse().body().string());
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }

            if (smssyncResponses != null) {
                Logger.log(TAG, "TaskCheckResponse: " + smssyncResponses.toString());
                mFileManager.appendAndClose("TaskCheckResponse: " + smssyncResponses.toString());

                if (smssyncResponses.getPayload() != null) {
                    String task = smssyncResponses.getPayload().getTask();
                    Logger.log(TAG, "Task " + task);
                    boolean secretOk = TextUtils.isEmpty(urlSecret) ||
                            urlSecret.equals(smssyncResponses.getPayload().getSecret());
                    if (secretOk && task.equals("send")) {
                        if (mPrefsFactory.messageResultsAPIEnable().get()) {
                            sendSMSWithMessageResultsAPIEnabled(syncUrl,
                                    smssyncResponses.getPayload().getMessages());
                        } else {
                            //backwards compatibility
                            sendSMSWithMessageResultsAPIDisabled(
                                    smssyncResponses.getPayload().getMessages());
                        }

                    } else {
                        Logger.log(TAG, mContext.getString(R.string.no_task));
                        logActivities(R.string.no_task);
                        mErrorMessage = mContext.getString(R.string.no_task);
                    }

                } else { // 'payload' data may not be present in JSON
                    Logger.log(TAG, mContext.getString(R.string.no_task));
                    logActivities(R.string.no_task);
                    mErrorMessage = mContext.getString(R.string.no_task);
                }
            }

            mFileManager.appendAndClose(
                    mContext.getString(R.string.finish_task_check) + " " + mErrorMessage + " for "
                            + syncUrl.getUrl());
        }
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    public static class Builder {

        private Context mContext;

        private PrefsFactory mPrefsFactory;

        private MessageHttpClient mMessageHttpClient;

        private MessageDataSourceFactory mMessageDataSourceFactory;

        private WebServiceDataSourceFactory mWebServiceDataSourceFactory;

        private FilterDataSourceFactory mFilterDataSourceFactory;

        private ProcessSms mProcessSms;

        private FileManager mFileManager;

        private ProcessMessageResult mProcessMessageResult;

        public Builder setContext(Context context) {
            mContext = context;
            return this;
        }

        public Builder setPrefsFactory(PrefsFactory prefsFactory) {
            mPrefsFactory = prefsFactory;
            return this;
        }

        public Builder setMessageHttpClient(MessageHttpClient messageHttpClient) {
            mMessageHttpClient = messageHttpClient;
            return this;
        }

        public Builder setMessageDataSourceFactory(
                MessageDataSourceFactory messageDataSourceFactory) {
            mMessageDataSourceFactory = messageDataSourceFactory;
            return this;
        }

        public Builder setWebServiceDataSourceFactory(
                WebServiceDataSourceFactory webServiceDataSourceFactory) {
            mWebServiceDataSourceFactory = webServiceDataSourceFactory;
            return this;
        }

        public Builder setFilterDataSourceFactory(FilterDataSourceFactory filterDataSourceFactory) {
            mFilterDataSourceFactory = filterDataSourceFactory;
            return this;
        }

        public Builder setProcessSms(ProcessSms processSms) {
            mProcessSms = processSms;
            return this;
        }

        public Builder setFileManager(FileManager fileManager) {
            mFileManager = fileManager;
            return this;
        }

        public Builder setProcessMessageResult(ProcessMessageResult processMessageResult) {
            mProcessMessageResult = processMessageResult;
            return this;
        }

        public PostMessage build() {
            return new PostMessage(mContext, mPrefsFactory, mMessageHttpClient,
                    mMessageDataSourceFactory, mWebServiceDataSourceFactory,
                    mFilterDataSourceFactory,
                    mProcessSms, mFileManager, mProcessMessageResult);
        }
    }
}
