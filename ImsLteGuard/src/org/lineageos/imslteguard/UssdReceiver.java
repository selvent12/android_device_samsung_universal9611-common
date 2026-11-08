/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.imslteguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionManager;

/** Told by telephony that a circuit-switched USSD request is about to be sent. */
public class UssdReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context.getApplicationContext() instanceof GuardApp app) {
            app.onUssdStarting(intent.getIntExtra("subId",
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID));
        }
    }
}
