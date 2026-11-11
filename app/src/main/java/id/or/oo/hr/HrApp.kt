package id.or.oo.hr

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.os.StatFs
import id.or.oo.hr.chat.ChatClient
import id.or.oo.hr.chat.ChatController
import id.or.oo.hr.chat.ConversationStore
import id.or.oo.hr.gateway.GatewayController
import id.or.oo.hr.gateway.GatewayLaunch
import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.gateway.LocalGatewayProbe
import id.or.oo.hr.gateway.LogBuffer
import id.or.oo.hr.gateway.active
import id.or.oo.hr.image.GhcrRegistry
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.imageInUse
import id.or.oo.hr.image.UpdateCheck
import id.or.oo.hr.image.UrlHttp
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.hr.profile.readStatus
import id.or.oo.hr.setup.CodexLogin
import id.or.oo.hr.setup.ControllerRestart
import id.or.oo.hr.setup.GatewayRestart
import id.or.oo.hr.setup.HermesCommand
import id.or.oo.hr.setup.ModelCatalog
import id.or.oo.hr.setup.ProfileSettings
import id.or.oo.hr.setup.SetupController
import id.or.oo.hr.setup.TaskHolder
import id.or.oo.hr.setup.TestRequest
import id.or.oo.pr.engine.NetworkDns
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.session.CommandSessionFactory
import id.or.oo.pr.session.NotificationEndedNotifier
import id.or.oo.pr.session.PrBootstrap
import id.or.oo.pr.session.PrCliCommand
import id.or.oo.pr.session.ProotSessionFactory
import id.or.oo.pr.session.ServiceForegroundController
import id.or.oo.pr.session.SessionHost
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet6Address

class HrApp : Application(), ProotHost, SessionHost {

    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val files: HrFiles by lazy { HrFiles(filesDir) }

    val profiles: ProfileStore by lazy { ProfileStore(files) }

    override val prefixDir: File get() = files.prefixDir

    override val homeDir: File get() = files.homeDir

    @get:JvmName("getProotPackageName")
    override val packageName: String get() = super.getPackageName()

    @get:JvmName("getProotCacheDir")
    override val cacheDir: File get() = super.getCacheDir()

    override val dnsServers: List<String>
        get() {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            val link = connectivity.getLinkProperties(network) ?: return emptyList()
            val hasIpv6DefaultRoute = link.routes.any {
                it.destination.prefixLength == 0 && it.destination.address is Inet6Address
            }
            return NetworkDns.usableServers(link.dnsServers, hasIpv6DefaultRoute)
        }

    override val sessions: SessionManager by lazy {
        SessionManager(
            ProotSessionFactory(this, listOf(profiles.bind())),
            ServiceForegroundController(this),
            NotificationEndedNotifier(this),
        )
    }

    override val notificationIcon: Int get() = R.drawable.ic_notification

    override fun openAppIntent(): Intent = Intent(this, MainActivity::class.java)

    val gatewayLog: LogBuffer by lazy { LogBuffer(secrets = { profiles.secrets() }) }

    val images: ImageStore by lazy {
        ImageStore(
            PrCliCommand(this),
            GhcrRegistry(UrlHttp()),
            files,
            imageScope,
            inUse = { imageInUse(sessions.running.value.isNotEmpty(), gateway.state.value) },
            restart = GatewayRestart { ControllerRestart(gateway.state, gateway::stop, gateway::start).restart() },
            gatewayActive = { gateway.state.value.active },
            stopGateway = { gateway.stop() },
            sessionsBlocking = { sessions.running.value.any { it != Hermes.GATEWAY_KEY } || sessions.tasks.value.isNotEmpty() || setup.step.value.busy },
            freeBytes = { StatFs(filesDir.path).availableBytes },
        )
    }

    val updates: UpdateCheck by lazy {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        UpdateCheck(
            GhcrRegistry(UrlHttp()),
            images.installed,
            object : UpdateCheck.Store {
                override var lastCheckedAt: Long
                    get() = prefs.getLong(UPDATE_CHECKED_AT, 0L)
                    set(value) = prefs.edit().putLong(UPDATE_CHECKED_AT, value).apply()
                override var lastLatestTag: String?
                    get() = prefs.getString(UPDATE_LATEST_TAG, null)
                    set(value) = prefs.edit().putString(UPDATE_LATEST_TAG, value).apply()
                override var lastLatestVersion: String?
                    get() = prefs.getString(UPDATE_LATEST_VERSION, null)
                    set(value) = prefs.edit().putString(UPDATE_LATEST_VERSION, value).apply()
            },
            imageScope,
        )
    }

    val gateway: GatewayController by lazy {
        GatewayController(
            sessions,
            GatewayLaunch { prepareGateway() },
            LocalGatewayProbe(),
            profiles,
            gatewayLog,
            gatewayScope,
        ).also { controller ->
            gatewayScope.launch {
                controller.state.collect { if (it is GatewayState.Running) images.confirmRunning() }
            }
        }
    }

    val chat: ChatController by lazy {
        ChatController(
            ConversationStore(files.chatsDir),
            ChatClient(profiles::apiServerKey, profiles::secrets),
            gateway.state,
            gatewayScope,
            secrets = profiles::secrets,
        )
    }

    private val hermesCommand: HermesCommand by lazy { HermesCommand(PrCliCommand(this), profiles.bind()) { images.currentAlias() } }

    val setup: SetupController by lazy {
        val login = CodexLogin(
            hermesCommand,
            TaskHolder { label, onCancel ->
                val task = sessions.beginTask(label, onCancel)
                AutoCloseable { sessions.endTask(task) }
            },
            gatewayScope,
        )
        SetupController(
            login,
            ModelCatalog(hermesCommand),
            ProfileSettings(hermesCommand, profiles),
            TestRequest(UrlHttp(), profiles::apiServerKey, secrets = profiles::secrets),
            ControllerRestart(gateway.state, gateway::stop, gateway::start),
            profiles::hasProvider,
            gatewayScope,
            { readStatus(files.profileDir) },
            imageBusy = { images.operation.value?.running == true },
        )
    }

    val keepAwake: KeepAwake by lazy {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        KeepAwake(
            object : KeepAwake.Store {
                override var remembered: Boolean
                    get() = prefs.getBoolean(KEEP_AWAKE, false)
                    set(value) = prefs.edit().putBoolean(KEEP_AWAKE, value).apply()
            },
            sessions.running,
            sessions.keepCpuAwake,
            sessions::setKeepCpuAwake,
            gatewayScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        PrBootstrap(this, this, BOOTSTRAP_VERSION).run()
        profiles.ensureProfile()
        keepAwake
    }

    private fun prepareGateway(): SessionManager.SessionFactory {
        check(images.canLaunch) { "wait for the image operation to finish" }
        val alias = checkNotNull(images.currentAlias()) { "install the Hermes image first" }
        profiles.writeRootEnv(files.rootfs(alias))
        return CommandSessionFactory(
            this,
            alias,
            Hermes.GATEWAY_COMMAND,
            gatewayLog::append,
            listOf(profiles.bind()),
            signalWaitMs = Hermes.GATEWAY_SIGNAL_WAIT_MS,
        )
    }

    companion object {
        const val PREFS = "hr_prefs"
        private const val BOOTSTRAP_VERSION = 1
        private const val KEEP_AWAKE = "keep_cpu_awake"
        private const val UPDATE_CHECKED_AT = "update_checked_at"
        private const val UPDATE_LATEST_TAG = "update_latest_tag"
        private const val UPDATE_LATEST_VERSION = "update_latest_version"
    }
}
