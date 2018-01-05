/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.provider.Telephony.Sms.Intents;
import android.telephony.Rlog;
import android.telephony.SmsManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.cdma.CdmaInboundSmsHandler;
import com.android.internal.telephony.cdma.CdmaSMSDispatcher;
import com.android.internal.telephony.gsm.GsmInboundSmsHandler;
import com.android.internal.telephony.gsm.GsmSMSDispatcher;

import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 */
public class SmsDispatchersController extends Handler {
    private static final String TAG = "RIL_ImsSms";

    /** Radio is ON */
    private static final int EVENT_RADIO_ON = 11;

    /** IMS registration/SMS format changed */
    private static final int EVENT_IMS_STATE_CHANGED = 12;

    /** Callback from RIL_REQUEST_IMS_REGISTRATION_STATE */
    private static final int EVENT_IMS_STATE_DONE = 13;

    private SMSDispatcher mCdmaDispatcher;
    private SMSDispatcher mGsmDispatcher;

    private GsmInboundSmsHandler mGsmInboundSmsHandler;
    private CdmaInboundSmsHandler mCdmaInboundSmsHandler;

    private Phone mPhone;
    /** Outgoing message counter. Shared by all dispatchers. */
    private final SmsUsageMonitor mUsageMonitor;
    private final CommandsInterface mCi;
    private final Context mContext;

    /** true if IMS is registered and sms is supported, false otherwise.*/
    private boolean mIms = false;
    private String mImsSmsFormat = SmsConstants.FORMAT_UNKNOWN;

    public SmsDispatchersController(Phone phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        Rlog.d(TAG, "SmsDispatchersController created");

        mContext = phone.getContext();
        mUsageMonitor = usageMonitor;
        mCi = phone.mCi;
        mPhone = phone;

        // Create dispatchers, inbound SMS handlers and
        // broadcast undelivered messages in raw table.
        mCdmaDispatcher = new CdmaSMSDispatcher(phone, this);
        mGsmInboundSmsHandler = GsmInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone);
        mCdmaInboundSmsHandler = CdmaInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone, (CdmaSMSDispatcher) mCdmaDispatcher);
        mGsmDispatcher = new GsmSMSDispatcher(phone, this, mGsmInboundSmsHandler);
        SmsBroadcastUndelivered.initialize(phone.getContext(),
                mGsmInboundSmsHandler, mCdmaInboundSmsHandler);
        InboundSmsHandler.registerNewMessageNotificationActionHandler(phone.getContext());

        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);
    }

    /* Updates the phone object when there is a change */
    protected void updatePhoneObject(Phone phone) {
        Rlog.d(TAG, "In IMS updatePhoneObject ");
        mCdmaDispatcher.updatePhoneObject(phone);
        mGsmDispatcher.updatePhoneObject(phone);
        mGsmInboundSmsHandler.updatePhoneObject(phone);
        mCdmaInboundSmsHandler.updatePhoneObject(phone);
    }

    public void dispose() {
        mCi.unregisterForOn(this);
        mCi.unregisterForImsNetworkStateChanged(this);
        mGsmDispatcher.dispose();
        mCdmaDispatcher.dispose();
        mGsmInboundSmsHandler.dispose();
        mCdmaInboundSmsHandler.dispose();
    }

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_RADIO_ON:
            case EVENT_IMS_STATE_CHANGED: // received unsol
                mCi.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;

            case EVENT_IMS_STATE_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    updateImsInfo(ar);
                } else {
                    Rlog.e(TAG, "IMS State query failed with exp "
                            + ar.exception);
                }
                break;

            default:
                if (isCdmaMo()) {
                    mCdmaDispatcher.handleMessage(msg);
                } else {
                    mGsmDispatcher.handleMessage(msg);
                }
        }
    }

    private void setImsSmsFormat(int format) {
        // valid format?
        switch (format) {
            case PhoneConstants.PHONE_TYPE_GSM:
                mImsSmsFormat = SmsConstants.FORMAT_3GPP;
                break;
            case PhoneConstants.PHONE_TYPE_CDMA:
                mImsSmsFormat = SmsConstants.FORMAT_3GPP2;
                break;
            default:
                mImsSmsFormat = SmsConstants.FORMAT_UNKNOWN;
                break;
        }
    }

    private void updateImsInfo(AsyncResult ar) {
        int[] responseArray = (int[]) ar.result;
        setImsSmsFormat(responseArray[1]);
        mIms = responseArray[0] == 1 && !SmsConstants.FORMAT_UNKNOWN.equals(mImsSmsFormat);
        Rlog.d(TAG, "IMS registration state: " + mIms + " format: " + mImsSmsFormat);
    }

    /**
     * Inject an SMS PDU into the android platform.
     *
     * @param pdu is the byte array of pdu to be injected into android telephony layer
     * @param format is the format of SMS pdu (3gpp or 3gpp2)
     * @param receivedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully received by the
     *  android telephony layer. This intent is broadcasted at
     *  the same time an SMS received from radio is responded back.
     */
    @VisibleForTesting
    public void injectSmsPdu(byte[] pdu, String format, PendingIntent receivedIntent) {
        Rlog.d(TAG, "SmsDispatchersController:injectSmsPdu");
        try {
            // TODO We need to decide whether we should allow injecting GSM(3gpp)
            // SMS pdus when the phone is camping on CDMA(3gpp2) network and vice versa.
            android.telephony.SmsMessage msg =
                    android.telephony.SmsMessage.createFromPdu(pdu, format);

            // Only class 1 SMS are allowed to be injected.
            if (msg == null
                    || msg.getMessageClass() != android.telephony.SmsMessage.MessageClass.CLASS_1) {
                if (msg == null) {
                    Rlog.e(TAG, "injectSmsPdu: createFromPdu returned null");
                }
                if (receivedIntent != null) {
                    receivedIntent.send(Intents.RESULT_SMS_GENERIC_ERROR);
                }
                return;
            }

            AsyncResult ar = new AsyncResult(receivedIntent, msg, null);

            if (format.equals(SmsConstants.FORMAT_3GPP)) {
                Rlog.i(TAG, "SmsDispatchersController:injectSmsText Sending msg=" + msg
                        + ", format=" + format + "to mGsmInboundSmsHandler");
                mGsmInboundSmsHandler.sendMessage(InboundSmsHandler.EVENT_INJECT_SMS, ar);
            } else if (format.equals(SmsConstants.FORMAT_3GPP2)) {
                Rlog.i(TAG, "SmsDispatchersController:injectSmsText Sending msg=" + msg
                        + ", format=" + format + "to mCdmaInboundSmsHandler");
                mCdmaInboundSmsHandler.sendMessage(InboundSmsHandler.EVENT_INJECT_SMS, ar);
            } else {
                // Invalid pdu format.
                Rlog.e(TAG, "Invalid pdu format: " + format);
                if (receivedIntent != null) {
                    receivedIntent.send(Intents.RESULT_SMS_GENERIC_ERROR);
                }
            }
        } catch (Exception e) {
            Rlog.e(TAG, "injectSmsPdu failed: ", e);
            try {
                if (receivedIntent != null) {
                    receivedIntent.send(Intents.RESULT_SMS_GENERIC_ERROR);
                }
            } catch (CanceledException ex) {
                Rlog.e(TAG, "Failed to send result");
            }
        }
    }

    /**
     * Retry the message along to the radio.
     *
     * @param tracker holds the SMS message to send
     */
    public void sendRetrySms(SMSDispatcher.SmsTracker tracker) {
        String oldFormat = tracker.mFormat;

        // newFormat will be based on voice technology
        String newFormat =
                (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType())
                        ? mCdmaDispatcher.getFormat() : mGsmDispatcher.getFormat();

        // was previously sent sms format match with voice tech?
        if (oldFormat.equals(newFormat)) {
            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format matched new format (cdma)");
                mCdmaDispatcher.sendSms(tracker);
                return;
            } else {
                Rlog.d(TAG, "old format matched new format (gsm)");
                mGsmDispatcher.sendSms(tracker);
                return;
            }
        }

        // format didn't match, need to re-encode.
        HashMap map = tracker.getData();

        // to re-encode, fields needed are:  scAddr, destAddr, and
        //   text if originally sent as sendText or
        //   data and destPort if originally sent as sendData.
        if (!(map.containsKey("scAddr") && map.containsKey("destAddr")
                && (map.containsKey("text")
                || (map.containsKey("data") && map.containsKey("destPort"))))) {
            // should never come here...
            Rlog.e(TAG, "sendRetrySms failed to re-encode per missing fields!");
            tracker.onFailed(mContext, SmsManager.RESULT_ERROR_GENERIC_FAILURE, 0/*errorCode*/);
            return;
        }
        String scAddr = (String) map.get("scAddr");
        String destAddr = (String) map.get("destAddr");

        SmsMessageBase.SubmitPduBase pdu = null;
        //    figure out from tracker if this was sendText/Data
        if (map.containsKey("text")) {
            Rlog.d(TAG, "sms failed was text");
            String text = (String) map.get("text");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            }
        } else if (map.containsKey("data")) {
            Rlog.d(TAG, "sms failed was data");
            byte[] data = (byte[]) map.get("data");
            Integer destPort = (Integer) map.get("destPort");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            }
        }

        // replace old smsc and pdu with newly encoded ones
        map.put("smsc", pdu.encodedScAddress);
        map.put("pdu", pdu.encodedMessage);

        SMSDispatcher dispatcher = (isCdmaFormat(newFormat))
                ? mCdmaDispatcher : mGsmDispatcher;

        tracker.mFormat = dispatcher.getFormat();
        dispatcher.sendSms(tracker);
    }

    public boolean isIms() {
        return mIms;
    }

    public String getImsSmsFormat() {
        return mImsSmsFormat;
    }

    /**
     * Determines whether or not to use CDMA format for MO SMS.
     * If SMS over IMS is supported, then format is based on IMS SMS format,
     * otherwise format is based on current phone type.
     *
     * @return true if Cdma format should be used for MO SMS, false otherwise.
     */
    protected boolean isCdmaMo() {
        if (!isIms()) {
            // IMS is not registered, use Voice technology to determine SMS format.
            return (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType());
        }
        // IMS is registered with SMS support
        return isCdmaFormat(mImsSmsFormat);
    }

    /**
     * Determines whether or not format given is CDMA format.
     *
     * @param format
     * @return true if format given is CDMA format, false otherwise.
     */
    private boolean isCdmaFormat(String format) {
        return (mCdmaDispatcher.getFormat().equals(format));
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param destPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>.
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    protected void sendData(String destAddr, String scAddr, int destPort,
                            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendData(destAddr, scAddr, destPort, data, sentIntent, deliveryIntent);
        } else {
            mGsmDispatcher.sendData(destAddr, scAddr, destPort, data, sentIntent, deliveryIntent);
        }
    }

    /**
     * Send a text based SMS.
     *  @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>.
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     * @param messageUri optional URI of the message if it is already stored in the system
     * @param callingPkg the calling package name
     * @param persistMessage whether to save the sent message into SMS DB for a
     *   non-default SMS app.
     */
    public void sendText(String destAddr, String scAddr, String text,
                            PendingIntent sentIntent, PendingIntent deliveryIntent, Uri messageUri,
                            String callingPkg, boolean persistMessage) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent, messageUri,
                    callingPkg, persistMessage);
        } else {
            mGsmDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent, messageUri,
                    callingPkg, persistMessage);
        }
    }

    /**
     * Send a multi-part text based SMS.
     *  @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>
     *   <code>RESULT_ERROR_NO_SERVICE</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     * @param messageUri optional URI of the message if it is already stored in the system
     * @param callingPkg the calling package name
     * @param persistMessage whether to save the sent message into SMS DB for a
     *   non-default SMS app.
     */
    protected void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, Uri messageUri, String callingPkg,
            boolean persistMessage) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents,
                    messageUri, callingPkg, persistMessage);
        } else {
            mGsmDispatcher.sendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents,
                    messageUri, callingPkg, persistMessage);
        }
    }

    /**
     * Returns the premium SMS permission for the specified package. If the package has never
     * been seen before, the default {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER}
     * will be returned.
     * @param packageName the name of the package to query permission
     * @return one of {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_UNKNOWN},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_NEVER_ALLOW}, or
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ALWAYS_ALLOW}
     */
    public int getPremiumSmsPermission(String packageName) {
        return mUsageMonitor.getPremiumSmsPermission(packageName);
    }

    /**
     * Sets the premium SMS permission for the specified package and save the value asynchronously
     * to persistent storage.
     * @param packageName the name of the package to set permission
     * @param permission one of {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_NEVER_ALLOW}, or
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ALWAYS_ALLOW}
     */
    public void setPremiumSmsPermission(String packageName, int permission) {
        mUsageMonitor.setPremiumSmsPermission(packageName, permission);
    }

    public SmsUsageMonitor getUsageMonitor() {
        return mUsageMonitor;
    }
}
