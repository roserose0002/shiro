package org.jsecurity.web;

import org.jsecurity.DefaultSecurityManager;
import org.jsecurity.authc.Account;
import org.jsecurity.context.SecurityContext;
import org.jsecurity.context.support.PrincipalsSerializer;
import org.jsecurity.session.Session;
import org.jsecurity.session.SessionFactory;
import org.jsecurity.util.ThreadContext;
import org.jsecurity.web.support.CookieStore;
import org.jsecurity.web.support.DefaultWebSessionFactory;
import org.jsecurity.web.support.HttpContainerWebSessionFactory;
import org.jsecurity.web.support.SecurityWebSupport;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.net.InetAddress;
import java.util.List;


/**
 * @author Les Hazlewood
 * @since 0.2
 */
public class DefaultWebSecurityManager extends DefaultSecurityManager {

    public static final String HTTP_SESSION_MODE = "http";
    public static final String JSECURITY_SESSION_MODE = "jsecurity";

    /** The key that is used to store subject principals in the session. */
    public static final String PRINCIPALS_SESSION_KEY =
        DefaultWebSecurityManager.class.getName() + "_PRINCIPALS_SESSION_KEY";

    /** The key that is used to store whether or not the user is authenticated in the session. */
    public static final String AUTHENTICATED_SESSION_KEY =
        DefaultWebSecurityManager.class.getName() + "_AUTHENTICATED_SESSION_KEY";

    public static final String DEFAULT_REMEMBER_ME_COOKIE_NAME = "rememberMe";

    protected WebSessionFactory webSessionFactory = null;

    private String sessionMode = HTTP_SESSION_MODE; //default

    protected CookieStore<String> rememberMeCookieStore = null;

    public void init() {
        super.init();
        ensureRememberMeCookieStore();
    }

    public String getSessionMode() {
        return sessionMode;
    }

    public void setSessionMode(String sessionMode) {
        this.sessionMode = sessionMode;
    }

    protected boolean isHttpSessionMode() {
        return this.sessionMode.equals(HTTP_SESSION_MODE);
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        if (!(sessionFactory instanceof WebSessionFactory)) {
            String msg = "The " + getClass().getName() + " implementation requires its underlying SessionFactory " +
                "instance to also implement the " + WebSessionFactory.class.getName() + " interface as well.  " +
                "The SessionFactory instance in question is of type [" + sessionFactory.getClass().getName() + "].";
            throw new IllegalArgumentException(msg);
        }
        super.setSessionFactory(sessionFactory);
    }

    protected SessionFactory createSessionFactory() {
        DefaultWebSessionFactory webSessionFactory;

        if (isHttpSessionMode()) {
            webSessionFactory = new HttpContainerWebSessionFactory();
        } else {
            webSessionFactory = new DefaultWebSessionFactory();
        }

        webSessionFactory.setCacheProvider(getCacheProvider());
        webSessionFactory.init();

        return webSessionFactory;
    }

    public CookieStore<String> getRememberMeCookieStore() {
        return rememberMeCookieStore;
    }

    public void setRememberMeCookieStore(CookieStore<String> rememberMeCookieStore) {
        this.rememberMeCookieStore = rememberMeCookieStore;
    }

    protected void ensureRememberMeCookieStore() {
        CookieStore<String> cookieStore = getRememberMeCookieStore();
        if (cookieStore == null) {
            cookieStore = new CookieStore<String>(DEFAULT_REMEMBER_ME_COOKIE_NAME);
            cookieStore.setCheckRequestParams(false);
            setRememberMeCookieStore(cookieStore);
        }
    }


    /**
     * Since the identity is stored as a cookie, the login process must execute before any output is rendered to the
     * Servlet Response output stream (cookies are unwritable after the response header has already gone out to the
     * stream).
     *
     * @param account
     */
    protected void rememberIdentity(Account account) {
        ServletRequest request = ThreadContext.getServletRequest();
        ServletResponse response = ThreadContext.getServletResponse();
        String cookieValue = getRememberMeSerializer().serialize(account.getPrincipals());
        getRememberMeCookieStore().storeValue( cookieValue, request, response );
    }

    /**
     * Returns the raw {@link Session session} associated with the request, or <tt>null</tt> if there isn't one.
     *
     * <p>Implementation note: this implementation merely delegates to an internal <tt>WebSessionFactory</tt> to perform
     * the lookup.
     *
     * @param request  incoming ServletRequest
     * @param response outgoing ServletResponse
     * @return the raw {@link Session session} associated with the request, or <tt>null</tt> if there isn't one.
     */
    public Session getSession(ServletRequest request, ServletResponse response) {
        Session session = webSessionFactory.getSession(request, response);
        if (log.isTraceEnabled()) {
            if (session != null) {
                log.trace("webSessionFactory returned a Session instance of type [" + session.getClass().getName() + "]");
            } else {
                log.trace("webSessionFactory did not return a Session instance.");
            }
        }
        return session;
    }

    protected List getPrincipals(Session session) {
        List principals = null;
        if (session != null) {
            principals = (List) session.getAttribute(PRINCIPALS_SESSION_KEY);
        }
        return principals;
    }

    protected List getPrincipals(ServletRequest servletRequest, ServletResponse servletResponse, Session existing) {
        List principals = getPrincipals( existing );
        if ( principals == null ) {
            //check remember me:
            String principalsString = getRememberMeCookieStore().retrieveValue( servletRequest, servletResponse );
            if ( principalsString != null ) {
                PrincipalsSerializer serializer = getRememberMeSerializer();
                if ( serializer != null ) {
                    principals = (List)getRememberMeSerializer().deserialize( principalsString );
                    //TODO - store in session here?
                }
            }
        }
        return principals;
    }

    protected boolean isAuthenticated(Session session) {
        Boolean value = null;
        if (session != null) {
            value = (Boolean) session.getAttribute(AUTHENTICATED_SESSION_KEY);
        }
        return value != null && value;
    }

    protected boolean isAuthenticated(ServletRequest servletRequest, ServletResponse servletResponse, Session existing) {
        return isAuthenticated(existing);
    }

    public SecurityContext createSecurityContext() {
        ServletRequest request = ThreadContext.getServletRequest();
        ServletResponse response = ThreadContext.getServletResponse();
        return createSecurityContext(request, response);
    }

    public SecurityContext createSecurityContext(ServletRequest request, ServletResponse response) {
        Session session = getSession(request, response);
        return createSecurityContext(request, response, session);
    }

    public SecurityContext createSecurityContext(ServletRequest request, ServletResponse response, Session existing) {
        List principals = getPrincipals(request, response, existing);
        boolean authenticated = isAuthenticated(request, response, existing);
        return createSecurityContext(request, response, existing, principals, authenticated);
    }

    protected SecurityContext createSecurityContext(ServletRequest request,
                                                    ServletResponse response,
                                                    Session existing,
                                                    List principals,
                                                    boolean authenticated) {

        InetAddress inetAddress = SecurityWebSupport.getInetAddress(request);
        return createSecurityContext(principals, authenticated, inetAddress, existing);
    }

    protected void bind(SecurityContext secCtx) {
        ServletRequest request = ThreadContext.getServletRequest();
        ServletResponse response = ThreadContext.getServletResponse();
        bind(secCtx, request, response);
        super.bind(secCtx);
    }

    protected void bind(SecurityContext securityContext, ServletRequest request, ServletResponse response) {
        List allPrincipals = securityContext.getAllPrincipals();
        if (allPrincipals != null && !allPrincipals.isEmpty()) {
            Session session = securityContext.getSession();
            session.setAttribute(PRINCIPALS_SESSION_KEY, allPrincipals);
        } else {
            Session session = securityContext.getSession(false);
            if (session != null) {
                session.removeAttribute(PRINCIPALS_SESSION_KEY);
            }
        }

        if (securityContext.isAuthenticated()) {
            Session session = securityContext.getSession();
            session.setAttribute(AUTHENTICATED_SESSION_KEY, securityContext.isAuthenticated());
        } else {
            Session session = securityContext.getSession(false);
            if (session != null) {
                session.removeAttribute(AUTHENTICATED_SESSION_KEY);
            }
        }
    }
}
