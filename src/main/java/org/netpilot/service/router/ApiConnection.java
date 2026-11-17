package org.netpilot.service.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * One open RouterOS API session. Commands are tagged, so several streams ({@code listen}, a
 * {@code print} with an interval) run side by side on one connection; a reader thread hands each
 * reply to the handler of its tag. The password is only ever turned into bytes for the login word,
 * never into a String, and is never logged.
 */
public final class ApiConnection implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ApiConnection.class.getName());

    /** Why the API could not be used, in words the settings page can explain. */
    public static final class ApiException extends IOException {

        private static final long serialVersionUID = 1L;

        /** What went wrong. */
        public enum Kind {
            /** Nothing answers on the port: the api / api-ssl service is off or blocked. */
            UNREACHABLE,
            /** The router refused the name or password. */
            LOGIN_FAILED,
            /** The router's TLS certificate is not the one trusted for this router. */
            CERTIFICATE,
            /** The connection broke or the router sent something unexpected. */
            BROKEN
        }

        private final Kind kind;

        public ApiException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Map<String, Consumer<ApiProtocol.Reply>> handlers = new ConcurrentHashMap<>();
    private final AtomicInteger tags = new AtomicInteger();
    private volatile Consumer<Throwable> onClosed = e -> {
    };
    private volatile boolean closed;
    private Thread reader;

    private ApiConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /**
     * Connects and logs in.
     *
     * @param tls                use api-ssl (port 8729 by default), checked against the trusted
     *                           certificate fingerprint like the REST connection
     * @param trustedFingerprint the router's trusted SHA-256 fingerprint, for TLS
     * @param password           not kept and not cleared here (the caller owns it)
     */
    public static ApiConnection open(String host, int port, boolean tls, String trustedFingerprint, String username,
            char[] password, Duration timeout) throws ApiException {
        Socket socket;
        try {
            socket = tls ? tlsSocket(trustedFingerprint) : new Socket();
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            socket.setTcpNoDelay(true);
            if (socket instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
        } catch (javax.net.ssl.SSLException e) {
            throw new ApiException(ApiException.Kind.CERTIFICATE, "TLS handshake failed", e);
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.UNREACHABLE, host + ":" + port + " did not answer", e);
        }
        ApiConnection connection = null;
        try {
            connection = new ApiConnection(socket);
            connection.login(username, password);
            socket.setSoTimeout(0); // streams may stay quiet for a long time; closing ends the read
            return connection;
        } catch (ApiException e) {
            closeQuietly(socket);
            throw e;
        } catch (IOException e) {
            closeQuietly(socket);
            throw new ApiException(ApiException.Kind.BROKEN, "Login conversation failed", e);
        }
    }

    private static Socket tlsSocket(String fingerprint) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new PinnedTrustManager(fingerprint)}, null);
            return context.getSocketFactory().createSocket();
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS is not available", e);
        }
    }

    /** RouterOS 6.43+: name and password in one {@code /login}. */
    private void login(String username, char[] password) throws IOException {
        byte[] secret = ApiProtocol.secretWord("=password=", password);
        try {
            synchronized (out) {
                ApiProtocol.writeSentence(out, List.of(ApiProtocol.word("/login"),
                        ApiProtocol.word("=name=" + username), secret));
                out.flush();
            }
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
        while (true) {
            ApiProtocol.Reply reply = ApiProtocol.Reply.of(ApiProtocol.readSentence(in));
            if (reply.is("!done")) {
                return;
            }
            if (reply.is("!trap") || reply.is("!fatal")) {
                throw new ApiException(ApiException.Kind.LOGIN_FAILED, reply.get("message"), null);
            }
        }
    }

    /** Starts reading replies; {@code closed} is told once, with the cause, when the connection ends. */
    public void start(Consumer<Throwable> whenClosed) {
        synchronized (startLock) {
            this.onClosed = Objects.requireNonNull(whenClosed);
            if (reader != null) {
                return;
            }
            reader = new Thread(this::readLoop, "netpilot-api-reader");
            reader.setDaemon(true);
            reader.start();
        }
    }

    private final Object startLock = new Object();

    private void readLoop() {
        Throwable cause = null;
        try {
            while (!closed) {
                ApiProtocol.Reply reply = ApiProtocol.Reply.of(ApiProtocol.readSentence(in));
                if (reply.is("!fatal")) {
                    throw new ApiException(ApiException.Kind.BROKEN, reply.get("message"), null);
                }
                reply.tag().map(handlers::get).ifPresent(handler -> {
                    try {
                        handler.accept(reply);
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "A live update handler failed", e);
                    }
                });
                if ((reply.is("!done") || reply.is("!trap")) && reply.tag().isPresent()) {
                    handlers.remove(reply.tag().get()); // the command ended (cancelled or refused)
                }
            }
        } catch (IOException | RuntimeException e) {
            cause = closed ? null : e;
        } finally {
            closeQuietly(socket);
            closed = true;
            onClosed.accept(cause);
        }
    }

    /**
     * Sends a command whose replies go to {@code handler} until it ends: {@code !re} for each
     * item or change, then {@code !done}, or {@code !trap} when refused.
     *
     * @param words the command and its attributes, e.g. {@code /interface/listen}
     * @return the tag, to {@link #cancel} it
     */
    public String send(List<String> words, Consumer<ApiProtocol.Reply> handler) throws IOException {
        String tag = String.valueOf(tags.incrementAndGet());
        handlers.put(tag, handler);
        List<byte[]> sentence = new ArrayList<>();
        words.forEach(w -> sentence.add(ApiProtocol.word(w)));
        sentence.add(ApiProtocol.word(".tag=" + tag));
        try {
            synchronized (out) {
                ApiProtocol.writeSentence(out, sentence);
                out.flush();
            }
        } catch (IOException e) {
            handlers.remove(tag);
            throw e;
        }
        return tag;
    }

    /** Stops a running command (a listen or an interval print). */
    public void cancel(String tag) throws IOException {
        send(List.of("/cancel", "=tag=" + tag), reply -> {
        });
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closing the API socket failed", e);
        }
    }
}
