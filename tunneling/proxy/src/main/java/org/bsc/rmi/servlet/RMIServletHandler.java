package org.bsc.rmi.servlet;

import lombok.Data;
import lombok.extern.java.Log;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.RMIClassLoader;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

/**
 * The default RMI socket factory contains several "fallback"
 * mechanisms which enable an RMI client to communicate with a remote
 * server.  When an RMI client initiates contact with a remote server,
 * it attempts to establish a connection using each of the following
 * protocols in turn, until one succeeds:
 * <p>
 * 1. Direct TCP connection.
 * 2. Direct HTTP connection.
 * 3. Proxy connection (SOCKS or HTTP).
 * 4. Connection on port 80 over HTTP to a CGI script.
 * 5. Proxy HTTP connection to CGI script on port 80.
 * <p>
 * The RMI ServletHandler can be used as replacement for the
 * java-rmi.cgi script that comes with the Java Development Kit (and
 * is invoked in protocols 4 and 5 above).  The java-rmi.cgi script
 * and the ServletHandler both function as proxy applications that
 * forward remote calls embedded in HTTP to local RMI servers which
 * service these calls.  The RMI ServletHandler enables RMI to tunnel
 * remote method calls over HTTP more efficiently than the existing
 * java-rmi.cgi script.  The ServletHandler is only loaded once from
 * the servlet administration utility.  The script, java-rmi.cgi, is
 * executed once every remote call.
 * <p>
 * The ServletHandler class contains methods for executing as a Java
 * servlet extension.  Because the RMI protocol only makes use of the
 * HTTP post command, the ServletHandler only supports the
 * <code>doPost</code> <code>HttpServlet</code> method.  The
 * <code>doPost</code> method of this class interprets a servlet
 * request's query string as a command of the form
 * "<command>=<parameters>".  These commands are represented by the
 * abstract interface, <code>RMICommandHandler</code>.  Once the
 * <code>doPost</code> method has parsed the requested command, it
 * calls the execute method on one of several command handlers in the
 * <code>commands</code> array.
 * <p>
 * The command that actually proxies remote calls is the
 * <code>ServletForwardCommand</code>.  When the execute method is
 * invoked on the ServletForwardCommand, the command will open a
 * connection on a local port specified by its <code>param</code>
 * parameter and will proceed to write the body of the relevant post
 * request into this connection.  It is assumed that an RMI server
 * (e.g. SampleRMIServer) is listening on the local port, "param."
 * The "forward" command will then read the RMI server's response and
 * send this information back to the RMI client as the body of the
 * response to the HTTP post method.
 * <p>
 * Because the ServletHandler uses a local socket to proxy remote
 * calls, the servlet has the ability to forward remote calls to local
 * RMI objects that reside in the ServletVM or outside of it.
 * <p>
 * Servlet documentation may be found at the following location:
 * <p>
 * http://jserv.javasoft.com/products/java-server/documentation/
 * webserver1.0.2/apidoc/Package-javax.servlet.http.html
 */
@Log
public class RMIServletHandler extends HttpServlet {

    public static final String PARAM_PREFIX  = "rmiservlethandler.";
    public static final String INITIAL_SERVER_CODEBASE  = PARAM_PREFIX.concat("initialServerCodebase");
    public static final String INITIAL_SERVER_CLASS     = PARAM_PREFIX.concat("initialServerClass");
    public static final String INITIAL_SERVER_BIND_NAME = PARAM_PREFIX.concat("initialServerBindName)");
    public static final String RMI_REMOTE_HOST          = PARAM_PREFIX.concat("remoteHost");

    @Data
    static class Parameters{
        String initialServerCodebase;
        String initialServerClass;
        String initialServerBindName;
        Optional<String> remoteHost = empty();

        public static Parameters of(ServletConfig config) {
            final Parameters result = new Parameters();
            result.initialServerCodebase   = ofNullable(config.getInitParameter(INITIAL_SERVER_CODEBASE)).orElse("");
            result.initialServerClass      = ofNullable(config.getInitParameter(INITIAL_SERVER_CLASS)).orElse("");
            result.initialServerBindName   = ofNullable(config.getInitParameter(INITIAL_SERVER_BIND_NAME)).orElse("");
            result.remoteHost = ofNullable(config.getInitParameter(RMI_REMOTE_HOST));
            return result;
        }
    }

    private Optional<Parameters> _optParameters = empty();

    /**
     *
     * @return
     */
    private Parameters getParameters() {
        return _optParameters.orElseThrow( () -> new IllegalStateException("parameters are not initialized!"));
    }

    /**
     * RMICommandHandler is the abstraction for an object that handles
     * a particular supported command (for example the "forward"
     * command "forwards" call information to a remote server on the
     * local machine).
     * <p>
     * The command handler is only used by the ServletHandler so the
     * interface is protected.
     */
    protected interface RMICommandHandler {

        /**
         * Return the string form of the command to be recognized in the
         * query string.
         */
        String getName();

        /**
         * Execute the command with the given string as parameter.
         */
        void execute(HttpServletRequest req, HttpServletResponse res,
                     String param)
                throws ServletClientException, ServletServerException, IOException;
    }

    /* construct table mapping command strings to handlers */
    private java.util.Map<String,RMICommandHandler> commandLookup = emptyMap();

    /**
     * Once loaded, Java Servlets continue to run until they are
     * unloaded or the webserver is stopped.  This example takes
     * advantage of the extended Servlet life-cycle and runs a remote
     * object in the Servlet VM.
     * <p>
     * To initialize this remote object the Servlet Administrator
     * should specify a set of parameters which will be used to
     * download and install an initial remote server (see readme.txt).
     * <p>
     * If configuration parameters are valid (not blank), the
     * servlet will attempt to load and start a remote object and a
     * registry in which the object may be bound.
     *
     * @param config Standard configuration object for an http servlet.
     * @throws ServletException Calling
     *                          <code>super.init(config)</code> may cause a servlet
     *                          exception to be thrown.
     */
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        /**
         * List of handlers for supported commands. A new command will be
         * created for every service request
         */
        final RMICommandHandler commands[] = {
                new ServletForwardCommand(),
                new ServletGethostnameCommand(),
                new ServletPingCommand(),
                new ServletTryHostnameCommand()
        };
        commandLookup = Arrays.stream( commands ).collect(toMap(cmd -> cmd.getName(), cmd -> cmd  ));

        try {

            this._optParameters = Optional.of( Parameters.of(config) );

            /* RMI requires that a local security manager be
             * responsible for the method invocations from remote
             * clients - we need to make sure a security manager is
             * installed.
             */
            if (System.getSecurityManager() == null) {
                System.setSecurityManager(new SecurityManager());
            }

            // create a registry if one is not running already.
            try {
                java.rmi.registry.LocateRegistry.createRegistry(1099);
            } catch (java.rmi.server.ExportException ee) {
                // registry already exists, we'll just use it.
                log.warning("registry already exists, we'll just use it.");
            } catch (RemoteException re) {
                log.log(Level.SEVERE, "init", re);
                //log.throwing(getClass().getName(), "init", re);
            }

            /**
             * Download and create a server object in a thread so we
             * do not interfere with other servlets.  Allow init
             * method to return more quickly.
             */
            //new Thread(this::createRMISampleServer).start();

            log.info("RMI Servlet Handler loaded sucessfully.");

        } catch (Exception e) {
            log.log(Level.SEVERE, "init", e);
            //log.throwing(getClass().getName(), "init", e);
        }
    }

    /**
     * Create the sample RMI server.
     */
    public void createRMISampleServer() {

        try {
            final UnicastRemoteObject server = createRemoteObjectUsingDownloadedClass();
            if (server != null) {
                Naming.rebind( getParameters().getInitialServerBindName(), server);
                log.info("Remote object created successfully.");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Exception received while intalling object:", e);
            //log.throwing(getClass().getName(), "run", e);
        }
    }

    /**
     * Load and export an initial remote object. The implementation
     * class for this remote object should not be loaded from the
     * servlet's class path; instead it should be loaded from a URL
     * location that will be accessible from a remote client.  In the
     * case of this example, that location will be
     * <code>initialServerCodebase</code>
     */
    private UnicastRemoteObject createRemoteObjectUsingDownloadedClass() throws Exception {

        UnicastRemoteObject server = null;
        Class<?> serverClass = null;

        int MAX_RETRY = 5;
        int retry = 0;
        int sleep = 2000;

        URL codebaseUrl = new URL(getParameters().getInitialServerCodebase());

        while ((retry < MAX_RETRY) && (serverClass == null)) {
            try {
                log.info("Attempting to load remote class...");
                serverClass = RMIClassLoader.loadClass(codebaseUrl, getParameters().getInitialServerClass());

                // Before we instantiate the obj. make sure it
                // is a UnicastRemoteObject.
                if (!Class.forName("java.rmi.server.UnicastRemoteObject").isAssignableFrom(serverClass)) {
                    log.info("This example requires an instance of UnicastRemoteObject, remote object not exported.");
                } else {
                    log.info("Server class loaded successfully...");
                    server = ((UnicastRemoteObject) serverClass.newInstance());
                }

            } catch (ClassNotFoundException cnfe) {
                retry++;

                /**
                 * The class for the remote object could not be
                 * loaded, perhaps the webserver has not finished
                 * initializing itself yet. Try to load the class a
                 * few more times...
                 */
                if (retry >= MAX_RETRY) {
                    log.info("Failed to load remote server class. Remote object not exported... ");
                } else {
                    log.warning("Could not load remote class, trying again...");
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ie) {
                        log.warning( "interrupt exception ignored!");
                    }
                    continue;
                }
            }
        }
        return server;
    }

    /* NOTE: If you are using JDK1.2Beta4 or later, it is recommended
     * that you provide your servlet with a destroy method that will
     * unexport any remote objects that your servlet ever exports.  As
     * mentioned in the readme file for this example, it is not
     * possible to unexport remote objects in JDK1.1.x; there is no
     * method in the RMI 1.1 public API that will perform this task.
     * To restart remote objects in the servlet VM, you will have to
     * restart your webserver.  In JDK1.2x, the methods to unexport a
     * remote object are as follows:
     *
     * java.rmi.activation.Activatable.
     *                 unexportObject(Remote obj, boolean force)
     * java.rmi.server.UnicastRemoteObject.
     *                 unexportObject(Remote obj, boolean force)
     */

    /**
     * Execute the command given in the servlet request query string.
     * The string before the first '=' in the queryString is
     * interpreted as the command name, and the string after the first
     * '=' is the parameters to the command.
     *
     * @param req HTTP servlet request, contains incoming command and
     *            arguments
     * @param res HTTP servlet response
     * @throws ServletException and IOException when invoking
     *                          methods of <code>req<code> or <code>res<code>.
     */
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {

        try {

            // Command and parameter for this POST request.
            final String queryString = ofNullable(req.getQueryString()).orElse("");

            String command = queryString;
            String param = "";

            int delim = queryString.indexOf("=");

            if (delim > -1) {
                command = queryString.substring(0, delim);
                param = queryString.substring(delim + 1);
            }

            log.info( format("command: %s param: %s", command, param ));

            // lookup command to execute on the client's behalf
            final RMICommandHandler handler = commandLookup.get(command);

            // execute the command
            if (handler != null)
                try {
                    handler.execute(req, res, param);
                } catch (ServletClientException e) {
                    returnClientError(res, "client error: %s", e.getMessage());
                    log.log(Level.SEVERE, "client error", e);
                    //log.throwing(getClass().getName(), "doPost", e);
                } catch (ServletServerException e) {
                    returnServerError(res, "internal server error: %s", e.getMessage());
                    log.log(Level.SEVERE, "internal Server Error", e);
                    //log.throwing(getClass().getName(), "doPost", e);
                }
            else
                returnClientError(res, "invalid command: %s", command);
        } catch (Exception e) {
            returnServerError(res, "internal error: %s", e.getMessage());
            log.log(Level.SEVERE, "internal Error", e);
            //log.throwing(getClass().getName(), "doPost", e);
        }
    }

    /**
     * Provide more intelligible errors for methods that are likely to
     * be called.  Let unsupported HTTP "do*" methods result in an
     * error generated by the super class.
     *
     * @param req http Servlet request, contains incoming command and
     *            arguments
     * @param res http Servlet response
     */
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {

        returnClientError(res,
                "GET Operation not supported: %s",
                "Can only forward POST requests.");
    }

    /**
     *
     * @param req
     * @param res
     * @throws IOException
     */
    public void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {

        returnClientError(res,
                "PUT Operation not supported: %s",
                "Can only forward POST requests.");
    }

    /**
     *
     * @return
     */
    public String getServletInfo() {
        return "RMI Call Forwarding Servlet Servlet.<br>\n";
    }

    /**
     * Return an HTML error message indicating there was error in
     * the client's request.
     *
     * @param res   Servlet response object through which <code>message</code>
     *              will be written to the client which invoked one of
     *              this servlet's methods.
     * @param messageFormat
     * @param msg
     * @throws IOException
     */
    private static void returnClientError(HttpServletResponse res, String messageFormat, String ...msg ) throws IOException {

        final String message = format( messageFormat, (Object[])msg );

        res.sendError(HttpServletResponse.SC_BAD_REQUEST,
                "<HTML><HEAD>" +
                        "<TITLE>Java RMI Client Error</TITLE>" +
                        "</HEAD>" +
                        "<BODY>" +
                        "<H1>Java RMI Client Error</H1>" +
                        message +
                        "</BODY></HTML>");

        log.severe(format( "%d Java RMI Client Error: %s", HttpServletResponse.SC_BAD_REQUEST, message));
    }

    /**
     * Return an HTML error message indicating an internal error
     * occurred here on the server.
     *
     * @param res     Servlet response object through which <code>message</code>
     *                will be written to the servlet client.
     * @param messageFormat Error message to be written to servlet client.
     */
    private static void returnServerError(HttpServletResponse res, String messageFormat, String ...msg) throws IOException {

        final String message = format( messageFormat, (Object[])msg );

        res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "<HTML><HEAD>" +
                        "<TITLE>Java RMI Server Error</TITLE>" +
                        "</HEAD>" +
                        "<BODY>" +
                        "<H1>Java RMI Server Error</H1>" +
                        message + "</BODY></HTML>");

        log.severe( format( "%d Java RMI Server Error: %s", HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message));
    }

    /*
     * The ServletHandler class is the only object that needs to access the
     * CommandHandler subclasses, so we write the commands internal to the
     * servlet handler.
     */

    /**
     * Class that has an execute command to forward request body to
     * local port on the server and send server reponse back to client.
     */
    protected class ServletForwardCommand implements RMICommandHandler {

        public String getName() {
            return "forward";
        }

        /**
         * Execute the forward command.  Forwards data from incoming servlet
         * request to a port on the local machine.  Presumably, an RMI server
         * will be reading the data that this method sends.
         *
         * @param req   The servlet request.
         * @param res   The servlet response.
         * @param param Port to which data will be sent.
         */
        public void execute(HttpServletRequest req, HttpServletResponse res, String param) throws ServletClientException, ServletServerException, IOException
        {

            // GUARD
            int port;

            try {
                port = Integer.parseInt(param);

                if (port <= 0 || port > 0xFFFF)
                    throw new ServletClientException( format("invalid port: %d", port));

                if (port < 1024)
                    throw new ServletClientException( format("permission denied for port: %d", port));

            } catch (NumberFormatException e) {
                throw new ServletClientException( format("invalid port number: %s",param));
            }


            byte buffer[];

            // read client's request body
            try (
                    final DataInputStream clientIn = new DataInputStream(req.getInputStream())
                )
            {
                buffer = new byte[req.getContentLength()];

                clientIn.readFully(buffer);

            } catch (EOFException e) {
                throw new ServletClientException("unexpected EOF reading request body");
            } catch (IOException e) {
                throw new ServletClientException("error reading request body");
            }

            final Function<String,InetAddress> getHostByName = (rmihost) -> {
                try {
                    return InetAddress.getByName(rmihost);
                } catch (UnknownHostException e) {
                    throw new Error(e);
                }
            };
            final Supplier<InetAddress> getLocalHost = () -> {
                try {
                    return InetAddress.getLocalHost();
                } catch (UnknownHostException e) {
                    throw new Error(e);
                }
            };

            // send to local server in HTTP
            try (
                    final Socket socket = new Socket(
                            getParameters().getRemoteHost()
                                    .map( getHostByName )
                                    .orElseGet( getLocalHost), port );
                    final DataOutputStream socketOut = new DataOutputStream(socket.getOutputStream());
                    final DataInputStream socketIn = new DataInputStream(socket.getInputStream())
                )
            {
                socketOut.writeBytes("POST / HTTP/1.0\r\n");
                socketOut.writeBytes( format("Content-length: %d\r\n\r\n", req.getContentLength()) );
                socketOut.write(buffer);
                socketOut.flush();

                final String key = "Content-length:".toLowerCase();
                boolean contentLengthFound = false;
                String line;
                int responseContentLength = -1;
                do {
                    line = socketIn.readLine();
                    if (line == null)
                        throw new ServletServerException("unexpected EOF reading server response");

                    if (line.toLowerCase().startsWith(key)) {
                        if (contentLengthFound)
                            ; // what would we want to do in this case??
                        responseContentLength = Integer.parseInt(line.substring(key.length()).trim());
                        contentLengthFound = true;
                    }
                } while ((line.length() != 0) &&
                        (line.charAt(0) != '\r') && (line.charAt(0) != '\n'));

                if (!contentLengthFound || responseContentLength < 0)
                    throw new ServletServerException("missing or invalid content length in server response");

                byte bufferIn[] = new byte[responseContentLength];
                try {
                    socketIn.readFully(bufferIn);

                } catch (EOFException e) {
                    throw new ServletServerException("unexpected EOF reading server response");
                }
                // send local server response back to servlet client
                res.setStatus(HttpServletResponse.SC_OK);
                res.setContentType("application/octet-stream");
                res.setContentLength(bufferIn.length);

                OutputStream out = res.getOutputStream();
                out.write(bufferIn);
                out.flush();

            } catch (IOException e) {
                throw new ServletServerException( format("error reading/writing to server: [%s]", e.getMessage()));
            }

        }
    }

    /**
     * Class that has an execute method to return the host name of the
     * server as the response body.
     */
    protected static class ServletGethostnameCommand implements RMICommandHandler {

        public String getName() {
            return "gethostname";
        }

        public void execute(HttpServletRequest req, HttpServletResponse res, String param) throws IOException
        {

            byte[] getHostStringBytes = req.getServerName().getBytes();

            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/octet-stream");
            res.setContentLength(getHostStringBytes.length);

            OutputStream out = res.getOutputStream();
            out.write(getHostStringBytes);
            out.flush();
        }
    }

    /**
     * Class that has an execute method to return an OK status to
     * indicate that connection was successful.
     */
    protected static class ServletPingCommand implements RMICommandHandler {

        public String getName() {
            return "ping";
        }

        public void execute(HttpServletRequest req, HttpServletResponse res, String param)
        {

            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/octet-stream");
            res.setContentLength(0);
        }
    }

    /**
     * Class that has an execute method to return a human readable
     * message describing which host name is available to local Java
     * VMs.
     */
    protected static class ServletTryHostnameCommand implements RMICommandHandler {

        public String getName() {
            return "hostname";
        }

        public void execute(HttpServletRequest req, HttpServletResponse res, String param) throws IOException
        {

            final PrintWriter pw = res.getWriter();

            pw.println("");
            pw.println("<HTML>" +
                    "<HEAD><TITLE>Java RMI Server Hostname Info" +
                    "</TITLE></HEAD>" +
                    "<BODY>");
            pw.println("<H1>Java RMI Server Hostname Info</H1>");
            pw.println("<H2>Local host name available to Java VM:</H2>");
            pw.print("<P>InetAddress.getLocalHost().getHostName()");
            try {
                String localHostName = InetAddress.getLocalHost().getHostName();

                pw.println(" = " + localHostName);
            } catch (UnknownHostException e) {
                pw.println(" threw java.net.UnknownHostException");
            }

            pw.println("<H2>Server host information obtained through Servlet " +
                    "interface from HTTP server:</H2>");
            pw.println("<P>SERVER_NAME = " + req.getServerName());
            pw.println("<P>SERVER_PORT = " + req.getServerPort());
            pw.println("</BODY></HTML>");
        }
    }

    /**
     * ServletClientException is thrown when an error is detected
     * in a client's request.
     */
    protected static class ServletClientException extends Exception {

        public ServletClientException(String s) {
            super(s);
        }
    }

    /**
     * ServletServerException is thrown when an error occurs here on the server.
     */
    protected static class ServletServerException extends Exception {

        public ServletServerException(String s) {
            super(s);
        }
    }
}
