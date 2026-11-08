/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.imslteguard;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Keeps VoLTE calls on LTE on modems that are never told about the AP IMS
 * registration (no IRadioIms). Such a voice-centric modem regards LTE as
 * having no voice service and periodically reselects to 2G, which silences
 * running VoLTE calls since SRVCC cannot happen. While IMS voice is
 * registered over LTE, 2G is disallowed; when LTE or IMS goes away, 2G is
 * allowed again so circuit-switched calls keep working.
 */
public class GuardApp extends Application {
    static final String TAG = "ImsLteGuard";

    private final SparseArray<SubGuard> mGuards = new SparseArray<>();
    private Handler mHandler;
    private Executor mExecutor;
    private SubscriptionManager mSubscriptionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            return;
        }
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mExecutor = mHandler::post;
        mSubscriptionManager = getSystemService(SubscriptionManager.class);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        updateSubscriptions();
                    }
                });
        mHandler.post(this::updateSubscriptions);
        Log.i(TAG, "started");
    }

    /** Keep 2G available for a circuit-switched USSD request on this subscription. */
    void onUssdStarting(int subId) {
        if (mHandler == null) {
            return;
        }
        mHandler.post(() -> {
            SubGuard guard = mGuards.get(subId);
            if (guard != null) {
                guard.startUssdHold();
            }
        });
    }

    private void updateSubscriptions() {
        List<SubscriptionInfo> infos = mSubscriptionManager.getActiveSubscriptionInfoList();
        SparseArray<SubGuard> keep = new SparseArray<>();
        if (infos != null) {
            for (SubscriptionInfo info : infos) {
                int subId = info.getSubscriptionId();
                SubGuard guard = mGuards.get(subId);
                if (guard == null) {
                    guard = new SubGuard(this, subId, mHandler, mExecutor);
                    guard.start();
                }
                keep.put(subId, guard);
            }
        }
        for (int i = 0; i < mGuards.size(); i++) {
            if (keep.get(mGuards.keyAt(i)) == null) {
                mGuards.valueAt(i).stop();
            }
        }
        mGuards.clear();
        for (int i = 0; i < keep.size(); i++) {
            mGuards.put(keep.keyAt(i), keep.valueAt(i));
        }
    }
}
