package id.or.oo.hr

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import id.or.oo.hr.gateway.GatewayController
import id.or.oo.hr.gateway.GatewayLaunch
import id.or.oo.hr.gateway.LocalGatewayProbe
import id.or.oo.hr.gateway.LogBuffer
import id.or.oo.hr.image.GhcrRegistry
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.imageInUse
import id.or.oo.hr.image.UrlHttp
import id.or.oo.hr.profile.ProfileStore
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

    val gatewayLog: LogBuffer by lazy { LogBuffer(secrets = { listOf(profiles.apiServerKey()) }) }

    val images: ImageStore by lazy {
        ImageStore(
            PrCliCommand(this),
            GhcrRegistry(UrlHttp()),
            files,
            imageScope,
            inUse = { imageInUse(sessions.running.value.isNotEmpty(), gateway.state.value) },
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
        )
    }

    override fun onCreate() {
        super.onCreate()
        PrBootstrap(this, this, BOOTSTRAP_VERSION).run()
        profiles.ensureProfile()
    }

    private fun prepareGateway(): SessionManager.SessionFactory {
        check(!images.busy) { "wait for the image operation to finish" }
        check(images.installed.value != null) { "install the Hermes image first" }
        profiles.writeRootEnv(files.rootfs(Hermes.CONTAINER))
        return CommandSessionFactory(
            this,
            Hermes.CONTAINER,
            Hermes.GATEWAY_COMMAND,
            gatewayLog::append,
            listOf(profiles.bind()),
            signalWaitMs = Hermes.GATEWAY_SIGNAL_WAIT_MS,
        )
    }

    private companion object {
        const val BOOTSTRAP_VERSION = 1
    }
}
