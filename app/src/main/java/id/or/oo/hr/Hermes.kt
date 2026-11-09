package id.or.oo.hr

object Hermes {
    const val CONTAINER = "hermes"
    const val TERMINAL_KEY = "hermes"
    const val GATEWAY_KEY = "hermes-gateway"
    const val REPOSITORY = "ghcr.io/oonid/hermes-agent"
    const val PULL_TAG = "gateway"
    const val PROFILE_NAME = "android"
    const val PROFILE_GUEST = "/root/.hermes/profiles/$PROFILE_NAME"
    const val ROOT_ENV = "root/.hermes/.env"
    const val GATEWAY_COMMAND = "cd /root && HERMES_HOME=/root/.hermes exec hermes gateway run"
    const val HOST = "127.0.0.1"
    const val PORT = 8642
    const val GATEWAY_SIGNAL_WAIT_MS = 65_000L
}
