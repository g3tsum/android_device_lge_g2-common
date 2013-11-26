/*
 * Copyright (C) 2012 The CyanogenMod Project
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

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;

import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DcFailCause;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;

import java.util.ArrayList;

/**
 * Custom Qualcomm No SimReady RIL using the latest Uicc stack
 *
 * {@hide}
 */
public class LgeLteRIL extends RIL implements CommandsInterface {
    protected HandlerThread mIccThread;
    protected String mAid;
    protected boolean mUSIM = false;
    boolean RILJ_LOGV = true;
    boolean RILJ_LOGD = true;

    private int mPendingNetwork = -1;
    private Message mPendingNetworkResponse;

    public LgeLteRIL(Context context, int networkMode, int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mSetPreferredNetworkType = -1;
        mQANElements = 5;
    }

    @Override
    public void
    iccIO (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIM_IO, result);

        if (mUSIM)
            path = path.replaceAll("7F20$","7FFF");

        rr.mParcel.writeInt(command);
        rr.mParcel.writeInt(fileid);
        rr.mParcel.writeString(path);
        rr.mParcel.writeInt(p1);
        rr.mParcel.writeInt(p2);
        rr.mParcel.writeInt(p3);
        rr.mParcel.writeString(data);
        rr.mParcel.writeString(pin2);
        rr.mParcel.writeString(mAid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccIO: "
                    + " aid: " + mAid + " "
                    + requestToString(rr.mRequest)
                    + " 0x" + Integer.toHexString(command)
                    + " 0x" + Integer.toHexString(fileid) + " "
                    + " path: " + path + ","
                    + p1 + "," + p2 + "," + p3);

        send(rr);
    }

    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus ca;

        IccCardStatus status = new IccCardStatus();
        int cardState = p.readInt();
        /* Standard stack doesn't recognize REMOVED and SIM_DETECT_INSERTED,
         * so convert them to ABSENT and PRESENT to trigger the hot-swapping 
         * check */
        if (cardState > 2) {
            cardState -= 3;
        }
        status.setCardState(cardState);
        status.setUniversalPinState(p.readInt());
        status.mGsmUmtsSubscriptionAppIndex = p.readInt();
        status.mCdmaSubscriptionAppIndex = p.readInt();
        status.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        status.mApplications = new IccCardApplicationStatus[numApplications];

        for (int i = 0; i < numApplications; i++) {
            ca = new IccCardApplicationStatus();
            ca.app_type = ca.AppTypeFromRILInt(p.readInt());
            ca.app_state = ca.AppStateFromRILInt(p.readInt());
            ca.perso_substate = ca.PersoSubstateFromRILInt(p.readInt());
            ca.aid = p.readString();
            ca.app_label = p.readString();
            ca.pin1_replaced = p.readInt();
            ca.pin1 = ca.PinStateFromRILInt(p.readInt());
            ca.pin2 = ca.PinStateFromRILInt(p.readInt());
            if (!needsOldRilFeature("skippinpukcount")) {
                p.readInt(); //remaining_count_pin1
                p.readInt(); //remaining_count_puk1
                p.readInt(); //remaining_count_pin2
                p.readInt(); //remaining_count_puk2
            }
            status.mApplications[i] = ca;
        }
        int appIndex = -1;
        if (mPhoneType == RILConstants.CDMA_PHONE &&
             status.mCdmaSubscriptionAppIndex >= 0) {
            appIndex = status.mCdmaSubscriptionAppIndex;
            Log.d(LOG_TAG, "This is a CDMA PHONE " + appIndex);
        } else {
            appIndex = status.mGsmUmtsSubscriptionAppIndex;
            Log.d(LOG_TAG, "This is a GSM PHONE " + appIndex);
        }

        if (cardState == RILConstants.SIM_ABSENT) {
            return status;
        }

        if (numApplications > 0) {
            IccCardApplicationStatus application = status.mApplications[appIndex];
            mAid = application.aid;
            mUSIM = application.app_type
                      == IccCardApplicationStatus.AppType.APPTYPE_USIM;
            mSetPreferredNetworkType = mPreferredNetworkType;

            if (TextUtils.isEmpty(mAid))
               mAid = "";
            Log.d(LOG_TAG, "mAid " + mAid);
        }

        return status;
    }
}

