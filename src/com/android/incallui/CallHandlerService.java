/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.incallui;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.android.services.telephony.common.AudioMode;
import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.ICallCommandService;
import com.android.services.telephony.common.ICallHandlerService;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

/**
 * Service used to listen for call state changes.
 */
public class CallHandlerService extends Service {

    private final static String TAG = CallHandlerService.class.getSimpleName();

    private static final int ON_UPDATE_CALL = 1;
    private static final int ON_UPDATE_MULTI_CALL = 2;
    private static final int ON_UPDATE_CALL_WITH_TEXT_RESPONSES = 3;
    private static final int ON_AUDIO_MODE = 4;
    private static final int ON_SUPPORTED_AUDIO_MODE = 5;
    private static final int ON_DISCONNECT_CALL = 6;
    private static final int ON_BRING_TO_FOREGROUND = 7;

    private static final int LARGEST_MSG_ID = ON_BRING_TO_FOREGROUND;


    private CallList mCallList;
    private Handler mMainHandler;
    private InCallPresenter mInCallPresenter;
    private AudioModeProvider mAudioModeProvider;

    @Override
    public void onCreate() {
        Log.i(this, "creating");
        super.onCreate();

        mMainHandler = new MainHandler();
        mCallList = CallList.getInstance();
        mAudioModeProvider = AudioModeProvider.getInstance();
        mInCallPresenter = InCallPresenter.getInstance();

        mInCallPresenter.setUp(getApplicationContext(), mCallList, mAudioModeProvider);
        Log.d(this, "onCreate finished");
    }

    @Override
    public void onDestroy() {
        Log.i(this, "destroying");

        // Remove all pending messages before nulling out handler
        for (int i = 1; i <= LARGEST_MSG_ID; i++) {
            mMainHandler.removeMessages(i);
        }
        mMainHandler = null;

        // The service gets disconnected under two circumstances:
        // 1. When there are no more calls
        // 2. When the phone app crashes.
        // If (2) happens, we can't leave the UI thinking that there are still
        // live calls.  So we will tell the callList to clear as a final request.
        mCallList.clearOnDisconnect();
        mCallList = null;

        mInCallPresenter.tearDown();
        mInCallPresenter = null;
        mAudioModeProvider = null;

        Log.d(this, "onDestroy finished");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(this, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(this, "onUnbind");

        // Returning true here means we get called on rebind, which is a feature we do not need.
        // Return false so that all reconections happen with a call to onBind().
        return false;
    }

    private final ICallHandlerService.Stub mBinder = new ICallHandlerService.Stub() {

        @Override
        public void setCallCommandService(ICallCommandService service) {
            try {
                Log.d(CallHandlerService.this, "onConnected: " + service.toString());
                CallCommandClient.getInstance().setService(service);
            } catch (Exception e) {
                Log.e(TAG, "Error processing setCallCommandservice() call", e);
            }
        }

        @Override
        public void onDisconnect(Call call) {
            try {
                Log.i(CallHandlerService.this, "onDisconnected: " + call);
                mMainHandler.sendMessage(mMainHandler.obtainMessage(ON_DISCONNECT_CALL, call));
            } catch (Exception e) {
                Log.e(TAG, "Error processing onDisconnect() call.", e);
            }
        }

        @Override
        public void onIncoming(Call call, List<String> textResponses) {
            try {
                Log.i(CallHandlerService.this, "onIncomingCall: " + call);

                // TODO(klp): Add text responses to the call object.
                Map.Entry<Call, List<String>> incomingCall
                        = new AbstractMap.SimpleEntry<Call, List<String>>(call, textResponses);
                Log.d("TEST", mMainHandler.toString());
                mMainHandler.sendMessage(mMainHandler.obtainMessage(
                        ON_UPDATE_CALL_WITH_TEXT_RESPONSES, incomingCall));
            } catch (Exception e) {
                Log.e(TAG, "Error processing onIncoming() call.", e);
            }
        }

        @Override
        public void onUpdate(List<Call> calls) {
            try {
                Log.i(CallHandlerService.this, "onUpdate " + calls.toString());

                // TODO(klp): Add use of fullUpdate to message
                mMainHandler.sendMessage(mMainHandler.obtainMessage(ON_UPDATE_MULTI_CALL, calls));
            } catch (Exception e) {
                Log.e(TAG, "Error processing onUpdate() call.", e);
            }
        }

        @Override
        public void onAudioModeChange(int mode, boolean muted) {
            try {
                Log.i(CallHandlerService.this, "onAudioModeChange : " + AudioMode.toString(mode));
                mMainHandler.sendMessage(mMainHandler.obtainMessage(ON_AUDIO_MODE, mode,
                            muted ? 1 : 0, null));
            } catch (Exception e) {
                Log.e(TAG, "Error processing onAudioModeChange() call.", e);
            }
        }

        @Override
        public void onSupportedAudioModeChange(int modeMask) {
            try {
                Log.i(CallHandlerService.this, "onSupportedAudioModeChange : " + AudioMode.toString(
                        modeMask));

                mMainHandler.sendMessage(mMainHandler.obtainMessage(ON_SUPPORTED_AUDIO_MODE,
                        modeMask, 0, null));
            } catch (Exception e) {
                Log.e(TAG, "Error processing onSupportedAudioModeChange() call.", e);
            }
        }

        @Override
        public void bringToForeground() {
            mMainHandler.sendMessage(mMainHandler.obtainMessage(ON_BRING_TO_FOREGROUND));
        }
    };

    /**
     * Handles messages from the service so that they get executed on the main thread, where they
     * can interact with UI.
     */
    private class MainHandler extends Handler {
        MainHandler() {
            super(getApplicationContext().getMainLooper(), null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            executeMessage(msg);
        }
    }

    private void executeMessage(Message msg) {
        if (msg.what > LARGEST_MSG_ID) {
            // If you got here, you may have added a new message and forgotten to
            // update LARGEST_MSG_ID
            Log.wtf(this, "Cannot handle message larger than LARGEST_MSG_ID.");
        }

        Log.d(this, "executeMessage " + msg.what);

        switch (msg.what) {
            case ON_UPDATE_CALL:
                mCallList.onUpdate((Call) msg.obj);
                break;
            case ON_UPDATE_MULTI_CALL:
                mCallList.onUpdate((List<Call>) msg.obj);
                break;
            case ON_UPDATE_CALL_WITH_TEXT_RESPONSES:
                AbstractMap.SimpleEntry<Call, List<String>> entry
                        = (AbstractMap.SimpleEntry<Call, List<String>>) msg.obj;
                mCallList.onIncoming(entry.getKey(), entry.getValue());
                break;
            case ON_DISCONNECT_CALL:
                mCallList.onDisconnect((Call) msg.obj);
                break;
            case ON_AUDIO_MODE:
                mAudioModeProvider.onAudioModeChange(msg.arg1, msg.arg2 == 1);
                break;
            case ON_SUPPORTED_AUDIO_MODE:
                mAudioModeProvider.onSupportedAudioModeChange(msg.arg1);
                break;
            case ON_BRING_TO_FOREGROUND:
                if (mInCallPresenter != null) {
                    mInCallPresenter.bringToForeground();
                }
                break;
            default:
                break;
        }
    }
}
