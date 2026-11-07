package ru.wearsleep.probe;

import androidx.health.services.client.PassiveListenerService;
import androidx.health.services.client.data.UserActivityInfo;

public final class ProbeService extends PassiveListenerService {
    @Override public void onUserActivityInfoReceived(UserActivityInfo info) {
        ProbeLog.append(this, "activity=" + info.getUserActivityState()
                + " effective_at=" + info.getStateChangeTime());
    }

    @Override public void onPermissionLost() {
        ProbeLog.append(this, "listener_permission_lost");
        ProbeLog.setActive(this, false);
        ProbeLog.setMessage(this, "Доступ к физической активности отозван");
    }
}
