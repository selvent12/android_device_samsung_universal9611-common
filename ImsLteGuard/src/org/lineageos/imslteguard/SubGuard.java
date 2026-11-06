/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.imslteguard;

import static org.lineageos.imslteguard.GuardApp.TAG;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.util.Log;

import java.util.concurrent.Executor;

/** Allowed network type policy for one subscription. See {@link GuardApp}. */
class SubGuard {
    private static final int REASON = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G;
    private static final long LTE_ONLY = TelephonyManager.NETWORK_TYPE_BITMASK_LTE
            | TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
            | TelephonyManager.NETWORK_TYPE_BITMASK_NR
            | TelephonyManager.NETWORK_TYPE_BITMASK_IWLAN;
    private static final long ALL = TelephonyManager.getAllNetworkTypesBitmask();

    /** IMS must be registered over LTE this long before 2G is disallowed. */
    private static final long RESTRICT_DELAY_MS = 3_000;
    /** No service at all for this long allows 2G again. */
    private static final long NO_SERVICE_DELAY_MS = 10_000;
    /** IMS unregistered for this long allows 2G again (CS fallback). */
    private static final long NO_IMS_DELAY_MS = 20_000;
    /** Emergency hold ends at the latest this long after dialing if no call shows up. */
    private static final long EMERGENCY_MAX_IDLE_MS = 120_000;
    private static final long IMS_RETRY_MS = 10_000;

    private final int mSubId;
    private final Handler mHandler;
    private final Executor mExecutor;
    private final TelephonyManager mTm;
    private final ImsMmTelManager mMmTel;

    private ServiceState mServiceState;
    private int mCallState = TelephonyManager.CALL_STATE_IDLE;
    private boolean mImsRegistered;
    private int mImsTransport = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
    private boolean mImsCallbackRegistered;
    private boolean mStopped;

    /** An emergency call was dialed: keep 2G allowed until it is over. */
    private boolean mEmergency;
    private boolean mEmergencyCallSeen;

    private boolean mRestricted;
    private Runnable mPending;
    private boolean mPendingRestrict;
    private long mPendingDeadline;

    private final TelephonyCallback mTelephonyCallback = new Callback();

    private final RegistrationManager.RegistrationCallback mImsCallback =
            new RegistrationManager.RegistrationCallback() {
                @Override
                public void onRegistered(ImsRegistrationAttributes attributes) {
                    mImsRegistered = true;
                    mImsTransport = attributes.getTransportType();
                    evaluate("ims registered");
                }

                @Override
                public void onRegistering(ImsRegistrationAttributes attributes) {
                    mImsRegistered = false;
                    evaluate("ims registering");
                }

                @Override
                public void onUnregistered(ImsReasonInfo info) {
                    mImsRegistered = false;
                    evaluate("ims unregistered");
                }
            };

    private final Runnable mRegisterImsCallback = this::registerImsCallback;
    private final Runnable mEmergencyTimeout = this::onEmergencyTimeout;

    SubGuard(Context context, int subId, Handler handler, Executor executor) {
        mSubId = subId;
        mHandler = handler;
        mExecutor = executor;
        mTm = context.getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
        mMmTel = ImsMmTelManager.createForSubscriptionId(subId);
    }

    void start() {
        // Fail safe: whatever was stored before (crash, reboot), start with 2G
        // allowed and only restrict again once IMS voice is confirmed on LTE.
        apply(false, "start");
        mTm.registerTelephonyCallback(mExecutor, mTelephonyCallback);
        registerImsCallback();
        Log.i(TAG, "sub " + mSubId + ": watching");
        evaluate("start");
    }

    void stop() {
        mStopped = true;
        mTm.unregisterTelephonyCallback(mTelephonyCallback);
        mHandler.removeCallbacks(mRegisterImsCallback);
        mHandler.removeCallbacks(mEmergencyTimeout);
        if (mImsCallbackRegistered) {
            mMmTel.unregisterImsRegistrationCallback(mImsCallback);
        }
        cancelPending();
        // Never leave a SIM that went away stuck without 2G.
        apply(false, "subscription removed");
    }

    private void registerImsCallback() {
        if (mStopped || mImsCallbackRegistered) {
            return;
        }
        try {
            mMmTel.registerImsRegistrationCallback(mExecutor, mImsCallback);
            mImsCallbackRegistered = true;
        } catch (ImsException | RuntimeException e) {
            Log.d(TAG, "sub " + mSubId + ": IMS not ready, retrying: " + e.getMessage());
            mHandler.postDelayed(mRegisterImsCallback, IMS_RETRY_MS);
            evaluate("ims not ready");
        }
    }

    private class Callback extends TelephonyCallback implements
            TelephonyCallback.ServiceStateListener, TelephonyCallback.CallStateListener,
            TelephonyCallback.OutgoingEmergencyCallListener {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            mServiceState = serviceState;
            evaluate("service state");
        }

        @Override
        public void onCallStateChanged(int state) {
            mCallState = state;
            if (mEmergency) {
                if (state != TelephonyManager.CALL_STATE_IDLE) {
                    mEmergencyCallSeen = true;
                } else if (mEmergencyCallSeen) {
                    endEmergency();
                    return;
                }
            }
            evaluate("call state");
        }

        @Override
        public void onOutgoingEmergencyCall(EmergencyNumber number, int subscriptionId) {
            startEmergency();
        }
    }

    private void startEmergency() {
        mEmergency = true;
        mEmergencyCallSeen = mCallState != TelephonyManager.CALL_STATE_IDLE;
        cancelPending();
        mHandler.removeCallbacks(mEmergencyTimeout);
        mHandler.postDelayed(mEmergencyTimeout, EMERGENCY_MAX_IDLE_MS);
        // Immediately, not after a delay: the call may need CS on 2G.
        apply(false, "emergency call");
    }

    private void onEmergencyTimeout() {
        if (mEmergency && !mEmergencyCallSeen) {
            Log.i(TAG, "sub " + mSubId + ": emergency hold timed out");
            endEmergency();
        }
    }

    private void endEmergency() {
        mEmergency = false;
        mEmergencyCallSeen = false;
        mHandler.removeCallbacks(mEmergencyTimeout);
        Log.i(TAG, "sub " + mSubId + ": emergency call over");
        evaluate("emergency over");
    }

    private NetworkRegistrationInfo psWwan() {
        if (mServiceState == null) {
            return null;
        }
        return mServiceState.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
    }

    private boolean onLte() {
        NetworkRegistrationInfo nri = psWwan();
        if (nri == null || !nri.isRegistered()) {
            return false;
        }
        int tech = nri.getAccessNetworkTechnology();
        return tech == TelephonyManager.NETWORK_TYPE_LTE
                || tech == TelephonyManager.NETWORK_TYPE_LTE_CA
                || tech == TelephonyManager.NETWORK_TYPE_NR;
    }

    private boolean inService() {
        if (mServiceState == null) {
            return true; // unknown yet, don't act on it
        }
        NetworkRegistrationInfo nri = psWwan();
        return mServiceState.getState() == ServiceState.STATE_IN_SERVICE
                || (nri != null && nri.isRegistered());
    }

    private boolean imsOverWwan() {
        return mImsRegistered && mImsTransport == AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
    }

    private void evaluate(String why) {
        if (mStopped || mEmergency) {
            return;
        }
        if (!mRestricted) {
            boolean restrict = onLte() && imsOverWwan()
                    && mCallState == TelephonyManager.CALL_STATE_IDLE;
            if (restrict) {
                schedule(true, RESTRICT_DELAY_MS, "IMS voice on LTE (" + why + ")");
            } else {
                cancelPending();
            }
        } else {
            if (!inService()) {
                schedule(false, NO_SERVICE_DELAY_MS, "no service (" + why + ")");
            } else if (!imsOverWwan()) {
                schedule(false, NO_IMS_DELAY_MS, "IMS not registered on LTE (" + why + ")");
            } else {
                cancelPending();
            }
        }
    }

    private void schedule(boolean restrict, long delayMs, String reason) {
        long deadline = SystemClock.uptimeMillis() + delayMs;
        if (mPending != null && mPendingRestrict == restrict && mPendingDeadline <= deadline) {
            return; // an earlier (or equal) deadline for the same action is already set
        }
        cancelPending();
        mPendingRestrict = restrict;
        mPendingDeadline = deadline;
        mPending = () -> {
            mPending = null;
            apply(restrict, reason);
        };
        mHandler.postAtTime(mPending, deadline);
    }

    private void cancelPending() {
        if (mPending != null) {
            mHandler.removeCallbacks(mPending);
            mPending = null;
        }
    }

    private void apply(boolean restrict, String reason) {
        try {
            mTm.setAllowedNetworkTypesForReason(REASON, restrict ? LTE_ONLY : ALL);
            mRestricted = restrict;
            Log.i(TAG, "sub " + mSubId + ": " + (restrict ? "LTE only" : "2G allowed")
                    + ": " + reason);
        } catch (RuntimeException e) {
            Log.e(TAG, "sub " + mSubId + ": cannot set allowed network types", e);
        }
    }
}
