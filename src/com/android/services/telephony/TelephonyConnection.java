/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;
import android.telecom.CallAudioState;
import android.telecom.ConferenceParticipant;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.StatusHints;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection.Capability;
import com.android.internal.telephony.Connection.PostDialListener;
import com.android.internal.telephony.gsm.SuppServiceNotification;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.phone.R;
import com.android.internal.telephony.PhoneConstants;

import java.lang.Override;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for CDMA and GSM connections.
 */
abstract class TelephonyConnection extends Connection {
    private static final int MSG_PRECISE_CALL_STATE_CHANGED = 1;
    private static final int MSG_RINGBACK_TONE = 2;
    private static final int MSG_HANDOVER_STATE_CHANGED = 3;
    private static final int MSG_DISCONNECT = 4;
    private static final int MSG_MULTIPARTY_STATE_CHANGED = 5;
    private static final int MSG_CONFERENCE_MERGE_FAILED = 6;
    private static final int MSG_SUPP_SERVICE_NOTIFY = 7;
    private static final int MSG_SET_VIDEO_STATE = 8;
    private static final int MSG_SET_LOCAL_VIDEO_CAPABILITY = 9;
    private static final int MSG_SET_REMOTE_VIDEO_CAPABILITY = 10;
    private static final int MSG_SET_VIDEO_PROVIDER = 11;
    private static final int MSG_SET_AUDIO_QUALITY = 12;
    private static final int MSG_SET_CONFERENCE_PARTICIPANTS = 13;
    private static final int MSG_PHONE_VP_ON = 14;
    private static final int MSG_PHONE_VP_OFF = 15;
    private static final int MSG_CONNECTION_EXTRAS_CHANGED = 16;
    private static final int MSG_SET_CONNECTION_CAPABILITY = 17;

    private boolean mIsVoicePrivacyOn = false;
    private SuppServiceNotification mSsNotification = null;
    private String[] mSubName = {"SIM1", "SIM2", "SIM3"};
    private String mDisplayName;
    private boolean mIsEmergencyNumber = false;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PRECISE_CALL_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_PRECISE_CALL_STATE_CHANGED");
                    updateState();
                    break;
                case MSG_HANDOVER_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_HANDOVER_STATE_CHANGED");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    com.android.internal.telephony.Connection connection =
                         (com.android.internal.telephony.Connection) ar.result;
                    if (mOriginalConnection != null) {
                        if (connection != null &&
                            ((connection.getAddress() != null &&
                            mOriginalConnection.getAddress() != null &&
                            mOriginalConnection.getAddress().contains(connection.getAddress())) ||
                            mOriginalConnection.getStateBeforeHandover() == connection.getState())) {
                            Log.d(TelephonyConnection.this, "SettingOriginalConnection " +
                                    mOriginalConnection.toString() + " with " + connection.toString());
                            setOriginalConnection(connection);
                            mWasImsConnection = false;
                        }
                    }
                    break;
                case MSG_RINGBACK_TONE:
                    Log.v(TelephonyConnection.this, "MSG_RINGBACK_TONE");
                    // TODO: This code assumes that there is only one connection in the foreground
                    // call, in other words, it punts on network-mediated conference calling.
                    if (getOriginalConnection() != getForegroundConnection()) {
                        Log.v(TelephonyConnection.this, "handleMessage, original connection is " +
                                "not foreground connection, skipping");
                        return;
                    }
                    setRingbackRequested((Boolean) ((AsyncResult) msg.obj).result);
                    break;
                case MSG_DISCONNECT:
                    updateState();
                    break;
                case MSG_MULTIPARTY_STATE_CHANGED:
                    boolean isMultiParty = (Boolean) msg.obj;
                    Log.i(this, "Update multiparty state to %s", isMultiParty ? "Y" : "N");
                    mIsMultiParty = isMultiParty;
                    if (isMultiParty) {
                        notifyConferenceStarted();
                    }
                case MSG_CONFERENCE_MERGE_FAILED:
                    notifyConferenceMergeFailed();
                    break;
                case MSG_SET_VIDEO_STATE:
                    int videoState = (int) msg.obj;
                    setVideoState(videoState);
                    break;

                case MSG_PHONE_VP_ON:
                    if (!mIsVoicePrivacyOn) {
                        mIsVoicePrivacyOn = true;
                        updateState();
                    }
                    break;

                case MSG_PHONE_VP_OFF:
                    if (mIsVoicePrivacyOn) {
                        mIsVoicePrivacyOn = false;
                        updateState();
                    }
                    break;

                case MSG_SET_VIDEO_PROVIDER:
                    VideoProvider videoProvider = (VideoProvider) msg.obj;
                    setVideoProvider(videoProvider);
                    break;

                case MSG_SET_AUDIO_QUALITY:
                    int audioQuality = (int) msg.obj;
                    setAudioQuality(audioQuality);
                    break;

                case MSG_SET_CONFERENCE_PARTICIPANTS:
                    List<ConferenceParticipant> participants = (List<ConferenceParticipant>) msg.obj;
                    updateConferenceParticipants(participants);
                    break;
                case MSG_SUPP_SERVICE_NOTIFY:
                    int phoneId = getPhone().getPhoneId();
                    Log.v(TelephonyConnection.this, "MSG_SUPP_SERVICE_NOTIFY on phoneId : "
                            +phoneId);
                    if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                        mSsNotification =
                                (SuppServiceNotification)((AsyncResult) msg.obj).result;
                        final String notificationText =
                                getSuppSvcNotificationText(mSsNotification, phoneId);
                        if (TelephonyManager.getDefault().getPhoneCount() > 1) {
                            SubscriptionInfo sub =
                                SubscriptionManager.from(TelephonyGlobals.getApplicationContext())
                                .getActiveSubscriptionInfoForSimSlotIndex(phoneId);
                            String displayName =  (sub != null) ?
                                sub.getDisplayName().toString() : mSubName[phoneId];
                            mDisplayName = displayName + ":" + notificationText;
                        } else {
                            mDisplayName = notificationText;
                        }
                        Toast.makeText(TelephonyGlobals.getApplicationContext(),
                                mDisplayName, Toast.LENGTH_LONG).show();
                        if (mSsNotification.history != null) {
                            final Bundle extras = getExtras();
                            if (extras != null) {
                                Log.v(TelephonyConnection.this,
                                        "Updating call history info in extras.");
                                extras.putStringArrayList(EXTRA_CALL_HISTORY_INFO,
                                     new ArrayList(Arrays.asList(mSsNotification.history)));
                                setExtras(extras);
                            }
                        }
                    } else {
                        Log.v(this,
                                "MSG_SUPP_SERVICE_NOTIFY event processing failed");
                    }

                case MSG_SET_CONNECTION_CAPABILITY:
                    setConnectionCapability(msg.arg1);
                    break;

                case MSG_CONNECTION_EXTRAS_CHANGED:
                    final Bundle extras = (Bundle) msg.obj;
                    updateExtras(extras);
                    break;
            }
        }
    };

    private String getSuppSvcNotificationText(SuppServiceNotification suppSvcNotification,
            int phoneId) {
        final int SUPP_SERV_NOTIFICATION_TYPE_MO = 0;
        final int SUPP_SERV_NOTIFICATION_TYPE_MT = 1;
        String callForwardTxt = "";
        if (suppSvcNotification != null) {
            switch (suppSvcNotification.notificationType) {
                // The Notification is for MO call
                case SUPP_SERV_NOTIFICATION_TYPE_MO:
                    callForwardTxt = getMoSsNotificationText(suppSvcNotification.code, phoneId);
                    break;

                // The Notification is for MT call
                case SUPP_SERV_NOTIFICATION_TYPE_MT:
                    callForwardTxt = getMtSsNotificationText(suppSvcNotification.code, phoneId);
                    break;

                default:
                    Log.v(this, "Received invalid Notification Type :"
                            + suppSvcNotification.notificationType);
                    break;
            }
        }
        return callForwardTxt;
    }

    private String getMtSsNotificationText(int code, int phoneId) {
        String callForwardTxt = "";
        Context context = TelephonyGlobals.getApplicationContext();
        switch (code) {
            case SuppServiceNotification.MT_CODE_FORWARDED_CALL:
                //This message is displayed on C when the incoming
                //call is forwarded from B
                callForwardTxt = context.getString(R.string.card_title_forwarded_MTcall);
                break;

            case SuppServiceNotification.MT_CODE_CUG_CALL:
                //This message is displayed on B, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = context.getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MT_CODE_CALL_ON_HOLD:
                //This message is displayed on B,when A makes call to B & puts it on
                // hold
                callForwardTxt = context.getString(R.string.card_title_callonhold);
                break;

            case SuppServiceNotification.MT_CODE_CALL_RETRIEVED:
                //This message is displayed on B,when A makes call to B, puts it on
                //hold & retrives it back.
                callForwardTxt = context.getString(R.string.card_title_callretrieved);
                break;

            case SuppServiceNotification.MT_CODE_MULTI_PARTY_CALL:
                //This message is displayed on B when the the call is changed as
                //multiparty
                callForwardTxt = context.getString(R.string.card_title_multipartycall);
                break;

            case SuppServiceNotification.MT_CODE_ON_HOLD_CALL_RELEASED:
                //This message is displayed on B, when A makes call to B, puts it on
                //hold & then releases it.
                callForwardTxt = context.getString(R.string.card_title_callonhold_released);
                break;

            case SuppServiceNotification.MT_CODE_FORWARD_CHECK_RECEIVED:
                //This message is displayed on C when the incoming call is forwarded
                //from B
                callForwardTxt = context.getString(R.string.card_title_forwardcheckreceived);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTING_ECT:
                //This message is displayed on B,when Call is connecting through
                //Explicit Cold Transfer
                callForwardTxt = context.getString(R.string.card_title_callconnectingect);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTED_ECT:
                //This message is displayed on B,when Call is connected through
                //Explicit Cold Transfer
                callForwardTxt = context.getString(R.string.card_title_callconnectedect);
                break;

            case SuppServiceNotification.MT_CODE_DEFLECTED_CALL:
                //This message is displayed on B when the incoming call is deflected
                //call
                callForwardTxt = context.getString(R.string.card_title_deflectedcall);
                break;

            case SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED:
                // This message is displayed on B when it is busy and the incoming call
                // gets forwarded to C
                callForwardTxt = context.getString(R.string.card_title_MTcall_forwarding);
                break;

            default :
               Log.v(this,"Received unsupported MT SS Notification :" + code
                      +" "+phoneId);
                break;
        }
        return callForwardTxt;
    }

    private String getMoSsNotificationText(int code, int phoneId) {
        String callForwardTxt = "";
        Context context = TelephonyGlobals.getApplicationContext();
        switch (code) {
            case SuppServiceNotification.MO_CODE_UNCONDITIONAL_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and unconditional forwarding is enabled.
                callForwardTxt = context.getString(R.string.card_title_unconditionalCF);
            break;

            case SuppServiceNotification.MO_CODE_SOME_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and conditional forwarding is enabled.
                callForwardTxt = context.getString(R.string.card_title_conditionalCF);
                break;

            case SuppServiceNotification.MO_CODE_CALL_FORWARDED:
                //This message is displayed on A when the outgoing call
                //actually gets forwarded to C
                callForwardTxt = context.getString(R.string.card_title_MOcall_forwarding);
                break;

            case SuppServiceNotification.MO_CODE_CALL_IS_WAITING:
                //This message is displayed on A when the B is busy on another call
                //and Call waiting is enabled on B
                callForwardTxt = context.getString(R.string.card_title_calliswaiting);
                break;

            case SuppServiceNotification.MO_CODE_CUG_CALL:
                //This message is displayed on A, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = context.getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MO_CODE_OUTGOING_CALLS_BARRED:
                //This message is displayed on A when outging is barred on A
                callForwardTxt = context.getString(R.string.card_title_outgoing_barred);
                break;

            case SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED:
                //This message is displayed on A, when A is calling B
                //& incoming is barred on B
                callForwardTxt = context.getString(R.string.card_title_incoming_barred);
                break;

            case SuppServiceNotification.MO_CODE_CLIR_SUPPRESSION_REJECTED:
                //This message is displayed on A, when CLIR suppression is rejected
                callForwardTxt = context.getString(R.string.card_title_clir_suppression_rejected);
                break;

            case SuppServiceNotification.MO_CODE_CALL_DEFLECTED:
                //This message is displayed on A, when the outgoing call
                //gets deflected to C from B
                callForwardTxt = context.getString(R.string.card_title_call_deflected);
                break;

            default:
                Log.v(this,"Received unsupported MO SS Notification :" + code
                        +" "+phoneId);
                break;
        }
        return callForwardTxt;
    }

    /**
     * A listener/callback mechanism that is specific communication from TelephonyConnections
     * to TelephonyConnectionService (for now). It is more specific that Connection.Listener
     * because it is only exposed in Telephony.
     */
    public abstract static class TelephonyConnectionListener {
        public void onOriginalConnectionConfigured(TelephonyConnection c) {}
    }

    private final PostDialListener mPostDialListener = new PostDialListener() {
        @Override
        public void onPostDialWait() {
            Log.v(TelephonyConnection.this, "onPostDialWait");
            if (mOriginalConnection != null) {
                setPostDialWait(mOriginalConnection.getRemainingPostDialString());
            }
        }

        @Override
        public void onPostDialChar(char c) {
            Log.v(TelephonyConnection.this, "onPostDialChar: %s", c);
            if (mOriginalConnection != null) {
                setNextPostDialChar(c);
            }
        }
    };

    /**
     * Listener for listening to events in the {@link com.android.internal.telephony.Connection}.
     */
    private final com.android.internal.telephony.Connection.Listener mOriginalConnectionListener =
            new com.android.internal.telephony.Connection.ListenerBase() {
        @Override
        public void onVideoStateChanged(int videoState) {
            mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState).sendToTarget();
        }

        /*
         * The {@link com.android.internal.telephony.Connection} has reported a change in
         * connection capability.
         * @param capabilities bit mask containing voice or video or both capabilities.
         */
        @Override
        public void onConnectionCapabilitiesChanged(int capabilities) {
            mHandler.obtainMessage(MSG_SET_CONNECTION_CAPABILITY,
                    capabilities, 0).sendToTarget();
        }

        /**
         * The {@link com.android.internal.telephony.Connection} has reported a change in the
         * video call provider.
         *
         * @param videoProvider The video call provider.
         */
        @Override
        public void onVideoProviderChanged(VideoProvider videoProvider) {
            mHandler.obtainMessage(MSG_SET_VIDEO_PROVIDER, videoProvider).sendToTarget();
        }

        /**
         * Used by {@link com.android.internal.telephony.Connection} to report a change in whether
         * the call is being made over a wifi network.
         *
         * @param isWifi True if call is made over wifi.
         */
        @Override
        public void onWifiChanged(boolean isWifi) {
            setWifi(isWifi);
        }

        /**
         * Used by the {@link com.android.internal.telephony.Connection} to report a change in the
         * audio quality for the current call.
         *
         * @param audioQuality The audio quality.
         */
        @Override
        public void onAudioQualityChanged(int audioQuality) {
            mHandler.obtainMessage(MSG_SET_AUDIO_QUALITY, audioQuality).sendToTarget();
        }
        /**
         * Handles a change in the state of conference participant(s), as reported by the
         * {@link com.android.internal.telephony.Connection}.
         *
         * @param participants The participant(s) which changed.
         */
        @Override
        public void onConferenceParticipantsChanged(List<ConferenceParticipant> participants) {
            mHandler.obtainMessage(MSG_SET_CONFERENCE_PARTICIPANTS, participants).sendToTarget();
        }

        /*
         * Handles a change to the multiparty state for this connection.
         *
         * @param isMultiParty {@code true} if the call became multiparty, {@code false}
         *      otherwise.
         */
        @Override
        public void onMultipartyStateChanged(boolean isMultiParty) {
            handleMultipartyStateChange(isMultiParty);
        }

        /**
         * Handles the event that the request to merge calls failed.
         */
        @Override
        public void onConferenceMergedFailed() {
            handleConferenceMergeFailed();
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            mHandler.obtainMessage(MSG_CONNECTION_EXTRAS_CHANGED, extras).sendToTarget();
        }
    };

    /* package */ com.android.internal.telephony.Connection mOriginalConnection;
    private Call.State mOriginalConnectionState = Call.State.IDLE;
    private Bundle mOriginalConnectionExtras = new Bundle();

    private boolean mWasImsConnection;

    /**
     * Tracks the multiparty state of the ImsCall so that changes in the bit state can be detected.
     */
    private boolean mIsMultiParty = false;

    /**
     * Determines if the {@link TelephonyConnection} has connection capabilities bitmask.
     * This will be initialized when {@link TelephonyConnection#setConnectionCapability()}} is called,
     * and used when {@link TelephonyConnection#updateConnectionCapabilities()}} is called,
     * ensuring the appropriate capabilities are set.  Since capabilities
     * can be rebuilt at any time it is necessary to track the connection capabilities between rebuild.
     * The capabilities (including connection capabilities) are communicated to the telecom
     * layer.
     */
    private int mConnectionCapability;

    /**
     * Determines if the {@link TelephonyConnection} is using wifi.
     * This is used when {@link TelephonyConnection#updateConnectionCapabilities} is called to
     * indicate wheter a call has the {@link Connection#CAPABILITY_WIFI} capability.
     */
    private boolean mIsWifi;

    /**
     * Determines the audio quality is high for the {@link TelephonyConnection}.
     * This is used when {@link TelephonyConnection#updateConnectionCapabilities}} is called to
     * indicate whether a call has the {@link Connection#CAPABILITY_HIGH_DEF_AUDIO} capability.
     */
    private boolean mHasHighDefAudio;

    /**
     * For video calls, indicates whether the outgoing video for the call can be paused using
     * the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     */
    private boolean mIsVideoPauseSupported;

    /**
     * Listeners to our TelephonyConnection specific callbacks
     */
    private final Set<TelephonyConnectionListener> mTelephonyListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<TelephonyConnectionListener, Boolean>(8, 0.9f, 1));

    protected TelephonyConnection(com.android.internal.telephony.Connection originalConnection) {
        if (originalConnection != null) {
            setOriginalConnection(originalConnection);
        }
    }

    /**
     * Creates a clone of the current {@link TelephonyConnection}.
     *
     * @return The clone.
     */
    public abstract TelephonyConnection cloneConnection();

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        // TODO: update TTY mode.
        if (getPhone() != null) {
            getPhone().setEchoSuppressionEnabled();
        }
    }

    @Override
    public void onStateChanged(int state) {
        Log.v(this, "onStateChanged, state: " + Connection.stateToString(state));
        updateStatusHints();
    }

    @Override
    public void onDisconnect() {
        Log.v(this, "onDisconnect");
        PhoneNumberUtils.resetCountryDetectorInfo();
        hangup(android.telephony.DisconnectCause.LOCAL);
    }

    /**
     * Notifies this Connection of a request to disconnect a participant of the conference managed
     * by the connection.
     *
     * @param endpoint the {@link Uri} of the participant to disconnect.
     */
    @Override
    public void onDisconnectConferenceParticipant(Uri endpoint) {
        Log.v(this, "onDisconnectConferenceParticipant %s", endpoint);

        if (mOriginalConnection == null) {
            return;
        }

        mOriginalConnection.onDisconnectConferenceParticipant(endpoint);
    }

    @Override
    public void onSeparate() {
        Log.v(this, "onSeparate");
        if (mOriginalConnection != null) {
            try {
                mOriginalConnection.separate();
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.separate failed with exception");
            }
        }
    }

    @Override
    public void onAbort() {
        Log.v(this, "onAbort");
        hangup(android.telephony.DisconnectCause.LOCAL);
    }

    @Override
    public void setLocalCallHold(boolean lchStatus) {
        TelephonyConnectionService.setLocalCallHold(getPhone(), lchStatus);
    }

    @Override
    public void onHold() {
        performHold();
    }

    @Override
    public void onUnhold() {
        performUnhold();
    }

    @Override
    public void onAnswer(int videoState) {
        Log.v(this, "onAnswer");
        if (isValidRingingCall() && getPhone() != null) {
            try {
                getPhone().acceptCall(videoState);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to accept call.");
            }
        }
    }

    @Override
    public void onReject() {
        Log.v(this, "onReject");
        if (isValidRingingCall()) {
            hangup(android.telephony.DisconnectCause.INCOMING_REJECTED);
        }
        super.onReject();
    }

    @Override
    public void onPostDialContinue(boolean proceed) {
        Log.v(this, "onPostDialContinue, proceed: " + proceed);
        if (mOriginalConnection != null) {
            if (proceed) {
                mOriginalConnection.proceedAfterWaitChar();
            } else {
                mOriginalConnection.cancelPostDial();
            }
        }
    }

    public void performHold() {
        Log.v(this, "performHold");
        // TODO: Can dialing calls be put on hold as well since they take up the
        // foreground call slot?
        if (Call.State.ACTIVE == mOriginalConnectionState) {
            Log.v(this, "Holding active call");
            try {
                Phone phone = mOriginalConnection.getCall().getPhone();
                Call ringingCall = phone.getRingingCall();

                // Although the method says switchHoldingAndActive, it eventually calls a RIL method
                // called switchWaitingOrHoldingAndActive. What this means is that if we try to put
                // a call on hold while a call-waiting call exists, it'll end up accepting the
                // call-waiting call, which is bad if that was not the user's intention. We are
                // cheating here and simply skipping it because we know any attempt to hold a call
                // while a call-waiting call is happening is likely a request from Telecom prior to
                // accepting the call-waiting call.
                // TODO: Investigate a better solution. It would be great here if we
                // could "fake" hold by silencing the audio and microphone streams for this call
                // instead of actually putting it on hold.
                if (ringingCall.getState() != Call.State.WAITING) {
                    phone.switchHoldingAndActive();
                }

                // TODO: Cdma calls are slightly different.
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to put call on hold.");
            }
        } else {
            Log.w(this, "Cannot put a call that is not currently active on hold.");
        }
    }

    public void performUnhold() {
        Log.v(this, "performUnhold");
        if (Call.State.HOLDING == mOriginalConnectionState) {
            try {
                // Here's the deal--Telephony hold/unhold is weird because whenever there exists
                // more than one call, one of them must always be active. In other words, if you
                // have an active call and holding call, and you put the active call on hold, it
                // will automatically activate the holding call. This is weird with how Telecom
                // sends its commands. When a user opts to "unhold" a background call, telecom
                // issues hold commands to all active calls, and then the unhold command to the
                // background call. This means that we get two commands...each of which reduces to
                // switchHoldingAndActive(). The result is that they simply cancel each other out.
                // To fix this so that it works well with telecom we add a minor hack. If we
                // have one telephony call, everything works as normally expected. But if we have
                // two or more calls, we will ignore all requests to "unhold" knowing that the hold
                // requests already do what we want. If you've read up to this point, I'm very sorry
                // that we are doing this. I didn't think of a better solution that wouldn't also
                // make the Telecom APIs very ugly.

                if (!hasMultipleTopLevelCalls()) {
                    mOriginalConnection.getCall().getPhone().switchHoldingAndActive();
                } else {
                    Log.i(this, "Skipping unhold command for %s", this);
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to release call from hold.");
            }
        } else {
            Log.w(this, "Cannot release a call that is not already on hold from hold.");
        }
    }

    public void performConference(TelephonyConnection otherConnection) {
        Log.d(this, "performConference - %s", this);
        if (getPhone() != null) {
            try {
                // We dont use the "other" connection because there is no concept of that in the
                // implementation of calls inside telephony. Basically, you can "conference" and it
                // will conference with the background call.  We know that otherConnection is the
                // background call because it would never have called setConferenceableConnections()
                // otherwise.
                getPhone().conference();
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to conference call.");
            }
        }
    }

    public void performAddParticipant(String participant) {
        Log.d(this, "performAddParticipant - %s", participant);
        if (getPhone() != null) {
            try {
                // We should send AddParticipant request using connection.
                // Basically, you can make call to conference with AddParticipant
                // request on single normal call.
                getPhone().addParticipant(participant);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to performAddParticipant.");
            }
        }
    }

    /**
     * Builds connection capabilities common to all TelephonyConnections. Namely, apply IMS-based
     * capabilities.
     */
    protected int buildConnectionCapabilities() {
        int callCapabilities = 0;
        if ((mOriginalConnection != null) && mOriginalConnection.isIncoming()) {
            callCapabilities |= CAPABILITY_SPEED_UP_MT_AUDIO;
        }
        if (isImsConnection()) {
            callCapabilities |= CAPABILITY_SUPPORT_HOLD;
            if (getState() == STATE_ACTIVE || getState() == STATE_HOLDING) {
                callCapabilities |= CAPABILITY_HOLD;
            }
        }

        // If the phone is in ECM mode, mark the call to indicate that the callback number should be
        // shown.
        Phone phone = getPhone();
        if (phone != null && phone.isInEcm()) {
            callCapabilities |= CAPABILITY_SHOW_CALLBACK_NUMBER;
        }
        return callCapabilities;
    }

    protected final void updateConnectionCapabilities() {
        int newCapabilities = buildConnectionCapabilities();

        newCapabilities = applyConnectionCapabilities(newCapabilities);
        newCapabilities = changeCapability(newCapabilities,
                CAPABILITY_HIGH_DEF_AUDIO, mHasHighDefAudio);
        newCapabilities = changeCapability(newCapabilities, CAPABILITY_WIFI, mIsWifi);
        newCapabilities = changeCapability(newCapabilities, CAPABILITY_CAN_PAUSE_VIDEO,
                mIsVideoPauseSupported && isVideoCapable());
        newCapabilities = applyConferenceTerminationCapabilities(newCapabilities);
        newCapabilities = applyVoicePrivacyCapabilities(newCapabilities);
        newCapabilities = applyAddParticipantCapabilities(newCapabilities);

        if (getConnectionCapabilities() != newCapabilities) {
            setConnectionCapabilities(newCapabilities);
        }
    }

    protected final void updateAddress() {
        updateConnectionCapabilities();
        Uri address;
        if (mOriginalConnection != null) {
            if (((getAddress() != null) &&
                    (getPhone().getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA)) &&
                    !isValidRingingCall()) {
                address = getAddressFromNumber(mOriginalConnection.getOrigDialString());
            } else {
                address = getAddressFromNumber(mOriginalConnection.getAddress());
            }
            int presentation = mOriginalConnection.getNumberPresentation();
            if (!Objects.equals(address, getAddress()) ||
                    presentation != getAddressPresentation()) {
                Log.v(this, "updateAddress, address changed");
                setAddress(address, presentation);
            }

            String name = mOriginalConnection.getCnapName();
            int namePresentation = mOriginalConnection.getCnapNamePresentation();
            if (!Objects.equals(name, getCallerDisplayName()) ||
                    namePresentation != getCallerDisplayNamePresentation()) {
                Log.v(this, "updateAddress, caller display name changed");
                setCallerDisplayName(name, namePresentation);
            }
        }
    }

    void onRemovedFromCallService() {
        // Subclass can override this to do cleanup.
    }

    void setOriginalConnection(com.android.internal.telephony.Connection originalConnection) {
        Log.v(this, "new TelephonyConnection, originalConnection: " + originalConnection);
        clearOriginalConnection();

        mOriginalConnection = originalConnection;
        getPhone().registerForPreciseCallStateChanged(
                mHandler, MSG_PRECISE_CALL_STATE_CHANGED, null);
        getPhone().registerForHandoverStateChanged(
                mHandler, MSG_HANDOVER_STATE_CHANGED, null);
        getPhone().registerForRingbackTone(mHandler, MSG_RINGBACK_TONE, null);
        getPhone().registerForDisconnect(mHandler, MSG_DISCONNECT, null);
        getPhone().registerForSuppServiceNotification(mHandler, MSG_SUPP_SERVICE_NOTIFY, null);
        getPhone().registerForInCallVoicePrivacyOn(mHandler, MSG_PHONE_VP_ON, null);
        getPhone().registerForInCallVoicePrivacyOff(mHandler, MSG_PHONE_VP_OFF, null);
        mOriginalConnection.addPostDialListener(mPostDialListener);
        mOriginalConnection.addListener(mOriginalConnectionListener);

        if (mOriginalConnection != null && mOriginalConnection.getAddress() != null) {
            mIsEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(mOriginalConnection.
                    getAddress());
        }

        // Set video state and capabilities
        setVideoState(mOriginalConnection.getVideoState());

        updateAddress();

        updateState();
        setConnectionCapability(mOriginalConnection.getConnectionCapabilities());
        setWifi(mOriginalConnection.isWifi());
        setVideoProvider(mOriginalConnection.getVideoProvider());
        setAudioQuality(mOriginalConnection.getAudioQuality());

        if (isImsConnection()) {
            mWasImsConnection = true;
        }
        mIsMultiParty = mOriginalConnection.isMultiparty();

        fireOnOriginalConnectionConfigured();
    }

    /**
     * Un-sets the underlying radio connection.
     */
    void clearOriginalConnection() {
        if (mOriginalConnection != null) {
            if (getPhone() != null) {
                getPhone().unregisterForPreciseCallStateChanged(mHandler);
                getPhone().unregisterForRingbackTone(mHandler);
                getPhone().unregisterForHandoverStateChanged(mHandler);
                getPhone().unregisterForDisconnect(mHandler);
                getPhone().unregisterForSuppServiceNotification(mHandler);
                getPhone().unregisterForInCallVoicePrivacyOn(mHandler);
                getPhone().unregisterForInCallVoicePrivacyOff(mHandler);
            }
            mOriginalConnection.removePostDialListener(mPostDialListener);
            mOriginalConnection.removeListener(mOriginalConnectionListener);
            mOriginalConnection = null;
        }
    }

    protected void hangup(int telephonyDisconnectCode) {
        if (mOriginalConnection != null) {
            try {
                // Hanging up a ringing call requires that we invoke call.hangup() as opposed to
                // connection.hangup(). Without this change, the party originating the call will not
                // get sent to voicemail if the user opts to reject the call.
                if (isValidRingingCall()) {
                    Call call = getCall();
                    if (call != null) {
                        call.hangup();
                    } else {
                        Log.w(this, "Attempting to hangup a connection without backing call.");
                    }
                } else {
                    // We still prefer to call connection.hangup() for non-ringing calls in order
                    // to support hanging-up specific calls within a conference call. If we invoked
                    // call.hangup() while in a conference, we would end up hanging up the entire
                    // conference call instead of the specific connection.
                    mOriginalConnection.hangup();
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.hangup failed with exception");
            }
        }
    }

    com.android.internal.telephony.Connection getOriginalConnection() {
        return mOriginalConnection;
    }

    protected Call getCall() {
        if (mOriginalConnection != null) {
            return mOriginalConnection.getCall();
        }
        return null;
    }

    Phone getPhone() {
        Call call = getCall();
        if (call != null) {
            return call.getPhone();
        }
        return null;
    }

    private boolean hasMultipleTopLevelCalls() {
        int numCalls = 0;
        Phone phone = getPhone();
        if (phone != null) {
            if (!phone.getRingingCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getForegroundCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getBackgroundCall().isIdle()) {
                numCalls++;
            }
        }
        return numCalls > 1;
    }

    private com.android.internal.telephony.Connection getForegroundConnection() {
        if (getPhone() != null) {
            return getPhone().getForegroundCall().getEarliestConnection();
        }
        return null;
    }

     /**
     * Checks for and returns the list of conference participants
     * associated with this connection.
     */
    public List<ConferenceParticipant> getConferenceParticipants() {
        if (mOriginalConnection == null) {
            Log.v(this, "Null mOriginalConnection, cannot get conf participants.");
            return null;
        }
        return mOriginalConnection.getConferenceParticipants();
    }

    /**
     * Checks to see the original connection corresponds to an active incoming call. Returns false
     * if there is no such actual call, or if the associated call is not incoming (See
     * {@link Call.State#isRinging}).
     */
    private boolean isValidRingingCall() {
        if (getPhone() == null) {
            Log.v(this, "isValidRingingCall, phone is null");
            return false;
        }

        Call ringingCall = getPhone().getRingingCall();
        if (!ringingCall.getState().isRinging()) {
            Log.v(this, "isValidRingingCall, ringing call is not in ringing state");
            return false;
        }

        if (ringingCall.getEarliestConnection() != mOriginalConnection) {
            Log.v(this, "isValidRingingCall, ringing call connection does not match");
            return false;
        }

        Log.v(this, "isValidRingingCall, returning true");
        return true;
    }

    protected void updateExtras(Bundle extras) {
        if (mOriginalConnection != null) {
            if (extras != null) {
                // Check if extras have changed and need updating.
                if (!areBundlesEqual(mOriginalConnectionExtras, extras)) {
                    if (Log.DEBUG) {
                        Log.d(TelephonyConnection.this, "Updating extras:");
                        for (String key : extras.keySet()) {
                            Object value = extras.get(key);
                            if (value instanceof String) {
                                Log.d(this, "updateExtras Key=" + Log.pii(key) +
                                             " value=" + Log.pii((String)value));
                            }
                        }
                    }
                    mOriginalConnectionExtras.clear();
                    mOriginalConnectionExtras.putAll(extras);
                    setExtras(extras);
                } else {
                    Log.d(this, "Extras update not required");
                }
            } else {
                Log.d(this, "updateExtras extras: " + Log.pii(extras));
            }
        }
    }

    private static boolean areBundlesEqual(Bundle extras, Bundle newExtras) {
        if (extras == null || newExtras == null) {
            return extras == newExtras;
        }

        if (extras.size() != newExtras.size()) {
            return false;
        }

        for(String key : extras.keySet()) {
            if (key != null) {
                final Object value = extras.get(key);
                final Object newValue = newExtras.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    void updateState() {
       updateState(false);
    }

    void updateState(boolean force) {
        if (mOriginalConnection == null) {
            return;
        }

        Call.State newState = mOriginalConnection.getState();
        Log.v(this, "Update state from %s to %s for %s", mOriginalConnectionState, newState, this);
        if (mOriginalConnectionState != newState || force) {
            mOriginalConnectionState = newState;
            switch (newState) {
                case IDLE:
                    break;
                case ACTIVE:
                    setActiveInternal();
                    break;
                case HOLDING:
                    setOnHold();
                    break;
                case DIALING:
                case ALERTING:
                    setDialing();
                    break;
                case INCOMING:
                case WAITING:
                    setRinging();
                    break;
                case DISCONNECTED:
                    if (mSsNotification != null) {
                        setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                                mOriginalConnection.getDisconnectCause(),
                                mOriginalConnection.getVendorDisconnectCause(),
                                mSsNotification.notificationType,
                                mSsNotification.code));
                        mSsNotification = null;
                        DisconnectCauseUtil.mNotificationCode = 0xFF;
                        DisconnectCauseUtil.mNotificationType = 0xFF;
                    } else {
                        setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                                mOriginalConnection.getDisconnectCause(),
                                mOriginalConnection.getVendorDisconnectCause()));
                    }
                    close();
                    break;
                case DISCONNECTING:
                    break;
            }
        }
        updateStatusHints();
        updateConnectionCapabilities();
        updateAddress();
        updateMultiparty();
    }

    /**
     * Checks for changes to the multiparty bit.  If a conference has started, informs listeners.
     */
    private void updateMultiparty() {
        if (mOriginalConnection == null) {
            return;
        }

        if (mIsMultiParty != mOriginalConnection.isMultiparty()) {
            mIsMultiParty = mOriginalConnection.isMultiparty();

            if (mIsMultiParty) {
                notifyConferenceStarted();
            }
        }
    }

    /**
     * Handles a failure when merging calls into a conference.
     * {@link com.android.internal.telephony.Connection.Listener#onConferenceMergedFailed()}
     * listener.
     */
    private void handleConferenceMergeFailed(){
        mHandler.obtainMessage(MSG_CONFERENCE_MERGE_FAILED).sendToTarget();
    }

    /**
     * Handles requests to update the multiparty state received via the
     * {@link com.android.internal.telephony.Connection.Listener#onMultipartyStateChanged(boolean)}
     * listener.
     * <p>
     * Note: We post this to the mHandler to ensure that if a conference must be created as a
     * result of the multiparty state change, the conference creation happens on the correct
     * thread.  This ensures that the thread check in
     * {@link com.android.internal.telephony.PhoneBase#checkCorrectThread(android.os.Handler)}
     * does not fire.
     *
     * @param isMultiParty {@code true} if this connection is multiparty, {@code false} otherwise.
     */
    private void handleMultipartyStateChange(boolean isMultiParty) {
        Log.i(this, "Update multiparty state to %s", isMultiParty ? "Y" : "N");
        mHandler.obtainMessage(MSG_MULTIPARTY_STATE_CHANGED, isMultiParty).sendToTarget();
    }

    private void setActiveInternal() {
        if (getState() == STATE_ACTIVE) {
            Log.w(this, "Should not be called if this is already ACTIVE");
            return;
        }

        // When we set a call to active, we need to make sure that there are no other active
        // calls. However, the ordering of state updates to connections can be non-deterministic
        // since all connections register for state changes on the phone independently.
        // To "optimize", we check here to see if there already exists any active calls.  If so,
        // we issue an update for those calls first to make sure we only have one top-level
        // active call.
        if (getConnectionService() != null) {
            for (Connection current : getConnectionService().getAllConnections()) {
                if (current != this && current instanceof TelephonyConnection) {
                    TelephonyConnection other = (TelephonyConnection) current;
                    if (other.getState() == STATE_ACTIVE) {
                        other.updateState();
                    }
                }
            }
        }
        setActive();
    }

    void close() {
        Log.v(this, "close");
        if (getPhone() != null) {
            if (getPhone().getState() == PhoneConstants.State.IDLE) {
                Log.i(this, "disable local call hold, if not already done by telecomm service");
                setLocalCallHold(false);
            }
        }
        clearOriginalConnection();
        destroy();
    }

    /**
     * Applies the Connection Capabilities bit-masks to the capabilities.
     *
     * @param capabilities The capabilities bit-mask.
     * @return The capabilities with Connection capabilities applied.
     */
    private int applyConnectionCapabilities(int capabilities) {
        return capabilities | getConnectionCapability();
    }

    private boolean isVideoCapable() {
        return can(mConnectionCapability, CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
                && can(mConnectionCapability, CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL);
    }

    /**
     * Applies capabilities specific to conferences termination to the
     * {@code ConnectionCapabilities} bit-mask.
     *
     * @param capabilities The {@code ConnectionCapabilities} bit-mask.
     * @return The capabilities with the IMS conference capabilities applied.
     */
    private int applyConferenceTerminationCapabilities(int capabilities) {
        int currentCapabilities = capabilities;

        // An IMS call cannot be individually disconnected or separated from its parent conference.
        // If the call was IMS, even if it hands over to GMS, these capabilities are not supported.
        if (!mWasImsConnection) {
            currentCapabilities |= CAPABILITY_DISCONNECT_FROM_CONFERENCE;
            currentCapabilities |= CAPABILITY_SEPARATE_FROM_CONFERENCE;
        }

        return currentCapabilities;
    }

    /**
     * Applies the voice privacy capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the voice privacy capabilities applied.
     */
    private int applyVoicePrivacyCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (mIsVoicePrivacyOn) {
            currentCapabilities = changeCapability(currentCapabilities,
                    CAPABILITY_VOICE_PRIVACY, mIsVoicePrivacyOn);
        } else {
            currentCapabilities = changeCapability(currentCapabilities,
                    CAPABILITY_VOICE_PRIVACY, mIsVoicePrivacyOn);
        }

        return currentCapabilities;
    }

    /**
     * Applies the add participant capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the add participant capabilities applied.
     */
    private int applyAddParticipantCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (getPhone() != null &&
                 getPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_IMS &&
                 !mIsEmergencyNumber) {
            currentCapabilities = applyCapability(currentCapabilities,
                    Connection.CAPABILITY_ADD_PARTICIPANT);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    Connection.CAPABILITY_ADD_PARTICIPANT);
        }

        return currentCapabilities;
    }

    private int applyCapability(int capabilities, int capability) {
        int newCapabilities = capabilities | capability;
        return newCapabilities;
    }

    private int removeCapability(int capabilities, int capability) {
        int newCapabilities = capabilities & ~capability;
        return newCapabilities;
    }


    /**
      * Called to get Connection Capability.This will return TelephonyConnection
      * Capability bitmask.
      * @See Connection.Capability.
      * @param mCapability returns TelephonyConnection Capability.
      */
    public int getConnectionCapability() {
        return mConnectionCapability;
    }

    /**
      * Called to set Connection Capability.This will take input parameter as bitmask
      * from internal telephony and maps the data to communicate with telecomm layer.
      * @See Connection.Capability.
      * @param capability The Capability bitmask which could be voice or video or both.
      */
    public void setConnectionCapability(int capability) {
        mConnectionCapability = changeCapability(mConnectionCapability,
                    CAPABILITY_SUPPORTS_DOWNGRADE_TO_VOICE_REMOTE,
                    can(capability, Capability.SUPPORTS_DOWNGRADE_TO_VOICE_REMOTE));

        mConnectionCapability = changeCapability(mConnectionCapability,
                    CAPABILITY_SUPPORTS_DOWNGRADE_TO_VOICE_LOCAL,
                    can(capability, Capability.SUPPORTS_DOWNGRADE_TO_VOICE_LOCAL));

        mConnectionCapability = changeCapability(mConnectionCapability,
                    CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL,
                    can(capability, Capability.SUPPORTS_VT_REMOTE_BIDIRECTIONAL));

        mConnectionCapability = changeCapability(mConnectionCapability,
                    CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL,
                    can(capability, Capability.SUPPORTS_VT_LOCAL_BIDIRECTIONAL));
        updateConnectionCapabilities();
    }

    /**
     * Sets whether the call is using wifi. Used when rebuilding the capabilities to set or unset
     * the {@link Connection#CAPABILITY_WIFI} capability.
     */
    public void setWifi(boolean isWifi) {
        mIsWifi = isWifi;
        updateConnectionCapabilities();
        updateStatusHints();
    }

    /**
     * Whether the call is using wifi.
     */
    boolean isWifi() {
        return mIsWifi;
    }

    /**
     * Sets the current call audio quality. Used during rebuild of the capabilities
     * to set or unset the {@link Connection#CAPABILITY_HIGH_DEF_AUDIO} capability.
     *
     * @param audioQuality The audio quality.
     */
    public void setAudioQuality(int audioQuality) {
        mHasHighDefAudio = audioQuality ==
                com.android.internal.telephony.Connection.AUDIO_QUALITY_HIGH_DEFINITION;
        updateConnectionCapabilities();
    }

    void resetStateForConference() {
        if (getState() == Connection.STATE_HOLDING) {
            if (mOriginalConnection.getState() == Call.State.ACTIVE) {
                setActive();
            }
        }
    }

    boolean setHoldingForConference() {
        if (getState() == Connection.STATE_ACTIVE) {
            setOnHold();
            return true;
        }
        return false;
    }

    /**
     * For video calls, sets whether this connection supports pausing the outgoing video for the
     * call using the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     *
     * @param isVideoPauseSupported {@code true} if pause state supported, {@code false} otherwise.
     */
    public void setVideoPauseSupported(boolean isVideoPauseSupported) {
        mIsVideoPauseSupported = isVideoPauseSupported;
    }

    /**
     * Whether the original connection is an IMS connection.
     * @return {@code True} if the original connection is an IMS connection, {@code false}
     *     otherwise.
     */
    protected boolean isImsConnection() {
        return getOriginalConnection() instanceof ImsPhoneConnection;
    }

    /**
     * Whether the original connection was ever an IMS connection, either before or now.
     * @return {@code True} if the original connection was ever an IMS connection, {@code false}
     *     otherwise.
     */
    public boolean wasImsConnection() {
        return mWasImsConnection;
    }

    private static Uri getAddressFromNumber(String number) {
        // Address can be null for blocked calls.
        if (number == null) {
            number = "";
        }
        return Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    }

    /**
     * Changes a capabilities bit-mask to add or remove a capability.
     *
     * @param capabilities The capabilities bit-mask.
     * @param capability The capability to change.
     * @param enabled Whether the capability should be set or removed.
     * @return The capabilities bit-mask with the capability changed.
     */
    private int changeCapability(int capabilities, int capability, boolean enabled) {
        if (enabled) {
            return capabilities | capability;
        } else {
            return capabilities & ~capability;
        }
    }

    private void updateStatusHints() {
        boolean isIncoming = isValidRingingCall();
        if (mIsWifi && (isIncoming || getState() == STATE_ACTIVE)) {
            int labelId = isIncoming
                    ? R.string.status_hint_label_incoming_wifi_call
                    : R.string.status_hint_label_wifi_call;

            Context context = getPhone().getContext();
            setStatusHints(new StatusHints(
                    context.getString(labelId),
                    Icon.createWithResource(
                            context.getResources(),
                            R.drawable.ic_signal_wifi_4_bar_24dp),
                    null /* extras */));
        } else {
            setStatusHints(null);
        }
    }

    /**
     * Register a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to add
     * @return The connection being listened to
     */
    public final TelephonyConnection addTelephonyConnectionListener(TelephonyConnectionListener l) {
        mTelephonyListeners.add(l);
        // If we already have an original connection, let's call back immediately.
        // This would be the case for incoming calls.
        if (mOriginalConnection != null) {
            fireOnOriginalConnectionConfigured();
        }
        return this;
    }

    /**
     * Remove a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to remove
     * @return The connection being listened to
     */
    public final TelephonyConnection removeTelephonyConnectionListener(
            TelephonyConnectionListener l) {
        if (l != null) {
            mTelephonyListeners.remove(l);
        }
        return this;
    }

    /**
     * Fire a callback to the various listeners for when the original connection is
     * set in this {@link TelephonyConnection}
     */
    private final void fireOnOriginalConnectionConfigured() {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onOriginalConnectionConfigured(this);
        }
    }

    /**
     * Creates a string representation of this {@link TelephonyConnection}.  Primarily intended for
     * use in log statements.
     *
     * @return String representation of the connection.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[TelephonyConnection objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" type:");
        if (isImsConnection()) {
            sb.append("ims");
        } else if (this instanceof com.android.services.telephony.GsmConnection) {
            sb.append("gsm");
        } else if (this instanceof CdmaConnection) {
            sb.append("cdma");
        }
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append(" capabilities:");
        sb.append(capabilitiesToString(getConnectionCapabilities()));
        sb.append(" address:");
        sb.append(Log.pii(getAddress()));
        sb.append(" originalConnection:");
        sb.append(mOriginalConnection);
        sb.append(" partOfConf:");
        if (getConference() == null) {
            sb.append("N");
        } else {
            sb.append("Y");
        }
        sb.append("]");
        return sb.toString();
    }
}
