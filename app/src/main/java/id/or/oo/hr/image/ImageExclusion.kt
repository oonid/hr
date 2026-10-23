package id.or.oo.hr.image

import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.gateway.active

fun imageInUse(sessionsRunning: Boolean, gateway: GatewayState): Boolean = sessionsRunning || gateway.active

fun canRemoveImage(inUse: Boolean, operation: ImageStore.Operation?): Boolean = !inUse && operation?.running != true

fun canStartSession(operation: ImageStore.Operation?): Boolean = operation?.running != true

class GrantWatcher(notifications: Boolean, exempt: Boolean) {
    private var notifications = notifications
    private var exempt = exempt

    fun update(notifications: Boolean, exempt: Boolean): Boolean {
        val granted = (notifications && !this.notifications) || (exempt && !this.exempt)
        this.notifications = notifications
        this.exempt = exempt
        return granted
    }
}
