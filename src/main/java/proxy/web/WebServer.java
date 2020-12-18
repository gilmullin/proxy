package proxy.web;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.NullSessionDataStore;
import org.eclipse.jetty.server.session.SessionCache;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import proxy.impl.AsyncProxy;

/**
 * @author rushan
 */
public class WebServer {
    private final Server server;

    public WebServer(int port, String path, int minWebThreads, int maxWebThreads, AsyncProxy proxy) {
        QueuedThreadPool threadPool = new QueuedThreadPool(maxWebThreads, minWebThreads);

        server = new Server(threadPool);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.setConnectors(new Connector[] { connector });

        ServletContextHandler apiContext = new ServletContextHandler();

        apiContext.addServlet(new ServletHolder(new MessageServlet(proxy)), path);

        SessionHandler sessionHandler = new SessionHandler();
        SessionCache cache = new DefaultSessionCache(sessionHandler);
        cache.setSessionDataStore(new NullSessionDataStore());
        sessionHandler.setSessionCache(cache);
        sessionHandler.setSessionIdManager(new DefaultSessionIdManager(server));
        apiContext.setSessionHandler(sessionHandler);

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { apiContext});
        server.setHandler(handlers);
    }

    public void start() throws Exception {
        server.start();
    }

    public void stop() throws Exception {
        this.server.stop();
    }
}
