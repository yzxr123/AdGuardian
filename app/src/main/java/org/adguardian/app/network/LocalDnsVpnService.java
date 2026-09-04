package org.adguardian.app.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.adguardian.app.MainActivity;
import org.adguardian.app.R;
import org.adguardian.app.debug.DebugLogStore;
import org.adguardian.app.debug.RuntimeState;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.settings.PreferenceStore;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LocalDnsVpnService extends VpnService {
    public static final String ACTION_START = "org.adguardian.app.action.START_DNS_VPN";
    public static final String ACTION_STOP = "org.adguardian.app.action.STOP_DNS_VPN";

    private static final String CHANNEL_ID = "adguardian_dns_filter";
    private static final int NOTIFICATION_ID = 1301;
    private static final String VPN_CLIENT_ADDRESS = "10.113.0.2";
    private static final String VPN_DNS_ADDRESS = "10.113.0.1";
    private static final int MAX_PACKET_BYTES = 32767;
    private static final int UPSTREAM_TIMEOUT_MS = 900;
    private static final int DNS_WORKERS = 2;
    private static final int DNS_QUEUE_LIMIT = 48;
    private static final long SAMPLE_LOG_INTERVAL_MS = 1500L;
    private static final long FAILURE_LOG_INTERVAL_MS = 1800L;

    private static volatile boolean running;

    private final Object outputLock = new Object();
    private ParcelFileDescriptor vpnInterface;
    private FileInputStream tunnelInput;
    private FileOutputStream tunnelOutput;
    private Thread tunnelThread;
    private ThreadPoolExecutor dnsExecutor;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network upstreamNetwork;
    private volatile InetAddress upstreamDns;
    private volatile long lastSampleLogUptime;
    private volatile long lastFailureLogUptime;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, LocalDnsVpnService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, LocalDnsVpnService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown("user-stop");
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        startInForeground();
        if (!running) {
            startVpn();
        }
        return Service.START_STICKY;
    }

    private void startVpn() {
        try {
            startUnderlyingNetworkTracking();

            Builder builder = new Builder()
                    .setSession("AdGuardian 网络广告过滤")
                    .setMtu(1500)
                    .addAddress(VPN_CLIENT_ADDRESS, 32)
                    .addDnsServer(VPN_DNS_ADDRESS)
                    .addRoute(VPN_DNS_ADDRESS, 32)
                    .setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                DebugLogStore.miss(this, "VPN_ESTABLISH_FAILED", "system returned null VPN interface");
                shutdown("establish-null");
                stopSelf();
                return;
            }

            tunnelInput = new FileInputStream(vpnInterface.getFileDescriptor());
            tunnelOutput = new FileOutputStream(vpnInterface.getFileDescriptor());
            dnsExecutor = new ThreadPoolExecutor(
                    DNS_WORKERS,
                    DNS_WORKERS,
                    20L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(DNS_QUEUE_LIMIT),
                    runnable -> {
                        Thread thread = new Thread(runnable, "AdGuardian-DNS-Upstream");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            running = true;
            Network initialNetwork = upstreamNetwork;
            if (initialNetwork != null) {
                setUnderlyingNetworks(new Network[]{initialNetwork});
            }
            tunnelThread = new Thread(this::tunnelLoop, "AdGuardian-DNS-Tunnel");
            tunnelThread.setDaemon(true);
            tunnelThread.start();

            DebugLogStore.info(
                    this,
                    "VPN_STARTED",
                    "mode=dns-only upstream=" + upstreamDescription()
                            + " blockedDomains=" + AdDomainBlocklist.count()
                            + " no-mitm=true"
            );
        } catch (Exception exception) {
            DebugLogStore.error(this, "VPN_START_ERROR", "failed to establish local DNS VPN", exception);
            shutdown("start-error");
            stopSelf();
        }
    }

    private void tunnelLoop() {
        byte[] packetBuffer = new byte[MAX_PACKET_BYTES];
        while (running) {
            try {
                int length = tunnelInput.read(packetBuffer);
                if (length <= 0) {
                    continue;
                }

                byte[] packet = new byte[length];
                System.arraycopy(packetBuffer, 0, packet, 0, length);
                DnsPacket.Query query = DnsPacket.parseIpv4UdpQuery(packet, length);
                if (query == null) {
                    logFailureThrottled("DNS_UNSUPPORTED_PACKET", "only IPv4 UDP DNS is handled in B4");
                    continue;
                }

                handleDnsQuery(query);
            } catch (IOException exception) {
                if (running) {
                    DebugLogStore.error(this, "VPN_TUN_READ_ERROR", "TUN read failed", exception);
                }
                break;
            } catch (RuntimeException exception) {
                DebugLogStore.error(this, "VPN_TUN_RUNTIME_ERROR", "unexpected DNS tunnel failure", exception);
            }
        }
    }

    private void handleDnsQuery(DnsPacket.Query query) {
        String host = query.host();
        String blockedBy = PreferenceStore.isMasterEnabled(this)
                && PreferenceStore.isTypeEnabled(this, AdType.NETWORK)
                ? AdDomainBlocklist.match(host)
                : "";

        if (!blockedBy.isEmpty()) {
            byte[] nxDomain = DnsPacket.buildNxDomain(query.dnsPayload());
            byte[] response = DnsPacket.buildIpv4UdpResponse(query, nxDomain);
            if (response != null && writeToTunnel(response)) {
                DebugLogStore.success(
                        this,
                        "DNS_BLOCKED",
                        "fg=" + RuntimeState.foregroundPackage()
                                + " domain=" + host
                                + " rule=" + blockedBy
                );
            }
            return;
        }

        maybeLogSuspiciousAllowed(host);
        ThreadPoolExecutor executor = dnsExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> forwardToUpstream(query));
    }

    private void forwardToUpstream(DnsPacket.Query query) {
        InetAddress upstream = upstreamDns;
        Network network = upstreamNetwork;
        if (upstream == null || network == null || !running) {
            logFailureThrottled("DNS_NO_UPSTREAM", "domain=" + query.host());
            return;
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protect(socket)) {
                logFailureThrottled("DNS_PROTECT_FAILED", "unable to bypass VPN for upstream socket");
                return;
            }
            network.bindSocket(socket);
            socket.setSoTimeout(UPSTREAM_TIMEOUT_MS);

            byte[] request = query.dnsPayload();
            DatagramPacket outbound = new DatagramPacket(request, request.length, upstream, 53);
            socket.send(outbound);

            byte[] responseBuffer = new byte[8192];
            DatagramPacket inbound = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(inbound);
            byte[] dnsResponse = new byte[inbound.getLength()];
            System.arraycopy(inbound.getData(), inbound.getOffset(), dnsResponse, 0, inbound.getLength());

            if (!DnsPacket.sameTransaction(request, dnsResponse)) {
                logFailureThrottled("DNS_TRANSACTION_MISMATCH", "domain=" + query.host());
                return;
            }

            byte[] response = DnsPacket.buildIpv4UdpResponse(query, dnsResponse);
            if (response != null) {
                writeToTunnel(response);
            }
        } catch (SocketTimeoutException timeout) {
            logFailureThrottled(
                    "DNS_UPSTREAM_TIMEOUT",
                    "domain=" + query.host() + " upstream=" + upstream.getHostAddress()
            );
        } catch (IOException exception) {
            logFailureThrottled(
                    "DNS_UPSTREAM_ERROR",
                    "domain=" + query.host() + " error=" + exception.getClass().getSimpleName()
            );
        }
    }

    private boolean writeToTunnel(byte[] packet) {
        if (packet == null || !running) {
            return false;
        }
        synchronized (outputLock) {
            try {
                tunnelOutput.write(packet);
                return true;
            } catch (IOException exception) {
                if (running) {
                    DebugLogStore.error(this, "VPN_TUN_WRITE_ERROR", "TUN write failed", exception);
                }
                return false;
            }
        }
    }

    private void startUnderlyingNetworkTracking() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            DebugLogStore.miss(this, "VPN_NO_CONNECTIVITY_MANAGER", "cannot observe underlying network");
            return;
        }

        selectAvailableUnderlying();

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ConnectivityManager manager = connectivityManager;
                if (manager != null) {
                    updateUnderlying(network, manager.getLinkProperties(network));
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                if (connectivityManager != null) {
                    updateUnderlying(network, linkProperties);
                }
            }

            @Override
            public void onLost(Network network) {
                if (connectivityManager != null && network.equals(upstreamNetwork)) {
                    upstreamNetwork = null;
                    upstreamDns = null;
                    selectAvailableUnderlying();
                }
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void selectAvailableUnderlying() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return;
        }

        Network active = manager.getActiveNetwork();
        if (isUsableUnderlying(manager, active)) {
            updateUnderlying(active, manager.getLinkProperties(active));
            if (upstreamDns != null) {
                return;
            }
        }

        for (Network network : manager.getAllNetworks()) {
            if (isUsableUnderlying(manager, network)) {
                updateUnderlying(network, manager.getLinkProperties(network));
                if (upstreamDns != null) {
                    return;
                }
            }
        }
    }

    private boolean isUsableUnderlying(ConnectivityManager manager, Network network) {
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    }

    private void updateUnderlying(Network network, LinkProperties properties) {
        if (network == null || properties == null) {
            return;
        }
        InetAddress dns = firstUsableDns(properties.getDnsServers());
        if (dns == null) {
            return;
        }

        boolean changed = !network.equals(upstreamNetwork)
                || upstreamDns == null
                || !dns.equals(upstreamDns);
        upstreamNetwork = network;
        upstreamDns = dns;

        if (running) {
            setUnderlyingNetworks(new Network[]{network});
        }
        if (changed) {
            DebugLogStore.info(
                    this,
                    "VPN_UPSTREAM_CHANGED",
                    "dns=" + dns.getHostAddress()
            );
        }
    }

    private InetAddress firstUsableDns(List<InetAddress> servers) {
        if (servers == null) {
            return null;
        }
        for (InetAddress server : servers) {
            if (server != null
                    && !VPN_DNS_ADDRESS.equals(server.getHostAddress())
                    && !server.isAnyLocalAddress()
                    && !server.isLoopbackAddress()) {
                return server;
            }
        }
        return null;
    }

    private String upstreamDescription() {
        InetAddress dns = upstreamDns;
        return dns == null ? "pending" : dns.getHostAddress();
    }

    private void maybeLogSuspiciousAllowed(String host) {
        if (!looksAdRelated(host)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSampleLogUptime < SAMPLE_LOG_INTERVAL_MS) {
            return;
        }
        lastSampleLogUptime = now;
        DebugLogStore.miss(
                this,
                "DNS_ADLIKE_NOT_BLOCKED",
                "fg=" + RuntimeState.foregroundPackage() + " domain=" + host
        );
    }

    private boolean looksAdRelated(String host) {
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return lower.contains(".ad.")
                || lower.startsWith("ad.")
                || lower.contains(".ads.")
                || lower.startsWith("ads.")
                || lower.contains("advert")
                || lower.contains("splash")
                || lower.contains("promotion")
                || lower.contains("promo.")
                || lower.contains("track.");
    }

    private void logFailureThrottled(String code, String message) {
        long now = SystemClock.uptimeMillis();
        if (now - lastFailureLogUptime < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLogUptime = now;
        DebugLogStore.miss(this, code, message);
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AdGuardian 网络过滤已开启")
                .setContentText("仅本机 DNS 广告域名过滤")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "网络广告过滤",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("AdGuardian 本机 DNS 广告过滤运行状态");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void shutdown(String reason) {
        boolean wasRunning = running;
        running = false;

        ThreadPoolExecutor executor = dnsExecutor;
        dnsExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }

        closeQuietly(tunnelInput);
        tunnelInput = null;
        closeQuietly(tunnelOutput);
        tunnelOutput = null;

        ParcelFileDescriptor localInterface = vpnInterface;
        vpnInterface = null;
        if (localInterface != null) {
            try {
                localInterface.close();
            } catch (IOException ignored) {
            }
        }

        Thread localThread = tunnelThread;
        tunnelThread = null;
        if (localThread != null && localThread != Thread.currentThread()) {
            localThread.interrupt();
        }

        ConnectivityManager manager = connectivityManager;
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        connectivityManager = null;
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }

        upstreamNetwork = null;
        upstreamDns = null;
        if (wasRunning) {
            DebugLogStore.info(this, "VPN_STOPPED", "reason=" + reason);
        }
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onRevoke() {
        DebugLogStore.info(this, "VPN_REVOKED", "system revoked VPN ownership");
        shutdown("revoked");
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown("destroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
