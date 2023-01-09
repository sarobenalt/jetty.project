package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ServletOutputStreamWriteTest
{
    private Server server;
    private LocalConnector localConnector;

    public void startServer(ServletContextHandler servletContextHandler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.addHandler(servletContextHandler);
        server.setHandler(contexts);
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testPrintlnString() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        ServletHolder holder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.setBufferSize(10);
                resp.getOutputStream().println(Math.PI);
            }
        });
        contextHandler.addServlet(holder, "/test/*");

        startServer(contextHandler);

        String rawRequest = "GET /test/ HTTP/1.1\r\n" +
            "Host: local\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(localConnector.getResponse(rawRequest));
        assertThat("response.status", response.getStatus(), is(HttpStatus.OK_200));
        assertThat("response.body", response.getContent(), is("3.141592653589793\r\n"));
    }

    interface HttpServletScenario
    {
        void accept(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException;
    }

    public static Stream<Arguments> writeScenarios()
    {
        final boolean NOT_COMMITTED = false;
        final boolean COMMITTED = true;
        return Stream.of(
            Arguments.of("Small println, fits in bufferSize",
                (HttpServletScenario)(req, resp) ->
                {
                    // bigger than output
                    resp.setBufferSize(10);
                    resp.setCharacterEncoding("utf-8");
                    resp.setContentType("text/plain");
                    resp.getOutputStream().println("Hello"); // only 5 + crlf = 7 characters
                    // no flush
                },
                NOT_COMMITTED)
            ,
            Arguments.of("Two writes, fits in bufferSize",
                (HttpServletScenario)(req, resp) ->
                {
                    // bigger than output
                    resp.setBufferSize(1024);
                    resp.setCharacterEncoding("utf-8");
                    resp.setContentType("text/plain");
                    OutputStream outputStream = resp.getOutputStream();
                    byte[] arr1 = new byte[600];
                    Arrays.fill(arr1, (byte)'a');
                    outputStream.write(arr1);

                    byte[] arr2 = new byte[400]; // fits in buffersize still
                    Arrays.fill(arr2, (byte)'b');
                    outputStream.write(arr2);
                    // no flush
                },
                NOT_COMMITTED),
            Arguments.of("One write of half bufferSize, original report",
                (HttpServletScenario)(req, resp) ->
                {
                    resp.setBufferSize(32768);
                    ServletOutputStream outputStream = resp.getOutputStream();
                    int dataSize = resp.getBufferSize() / 2;
                    byte arr[] = new byte[dataSize];
                    Arrays.fill(arr, (byte)'*');
                    String data = new String(arr, StandardCharsets.UTF_8);
                    outputStream.print(data);
                    // no flush
                },
                NOT_COMMITTED),
            Arguments.of("Three writes of half bufferSize",
                (HttpServletScenario)(req, resp) ->
                {
                    resp.setBufferSize(32768);
                    ServletOutputStream outputStream = resp.getOutputStream();
                    int dataSize = resp.getBufferSize() / 2;
                    for (int i = 0; i < 3; i++)
                    {
                        byte arr[] = new byte[dataSize];
                        Arrays.fill(arr, (byte)('a' + i));
                        String data = new String(arr, StandardCharsets.UTF_8);
                        outputStream.print(data);
                    }
                    // no flush
                },
                COMMITTED)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("writeScenarios")
    public void testWriteAggregate(@SuppressWarnings("unused") String description, HttpServletScenario httpServletScenario, boolean expectedCommitted) throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        ServletHolder holder = new ServletHolder(new HttpServlet()
        {
            Logger LOG = LoggerFactory.getLogger(ServletOutputStreamWriteTest.class);

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                httpServletScenario.accept(req, resp);
                LOG.info("resp.isCommitted={}", resp.isCommitted());
                assertThat("isCommitted", resp.isCommitted(), is(expectedCommitted));
            }
        });
        contextHandler.addServlet(holder, "/test/*");

        startServer(contextHandler);

        String rawRequest = "GET /test/ HTTP/1.1\r\n" +
            "Host: local\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        HttpTester.Response response = HttpTester.parseResponse(localConnector.getResponse(rawRequest));
        assertThat("response.status", response.getStatus(), is(HttpStatus.OK_200));
    }
}
