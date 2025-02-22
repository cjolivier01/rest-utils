package io.confluent.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.kafka.common.config.ConfigException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.jersey.servlet.ServletProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class ApplicationServerTest {

  static TestRestConfig testConfig;
  private static ApplicationServer<TestRestConfig> server;

  @Before
  public void setup() throws Exception {
    Properties props = new Properties();
    props.setProperty(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:0");

    testConfig = new TestRestConfig(props);
    server = new ApplicationServer<>(testConfig);
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  private TestRestConfig configBasic() {
    Properties props = new Properties();
    props.put(RestConfig.AUTHENTICATION_METHOD_CONFIG, RestConfig.AUTHENTICATION_METHOD_BASIC);
    props.put(RestConfig.AUTHENTICATION_REALM_CONFIG, "c3");
    props.put(RestConfig.AUTHENTICATION_ROLES_CONFIG, Collections.singletonList("Administrators"));

    return new TestRestConfig(props);
  }

  /* Ensure security handlers are confined to a single context */
  @Test
  public void testSecurityHandlerIsolation() throws Exception {
    TestApp app1 = new TestApp("/app1");
    TestApp app2 = new TestApp(configBasic(), "/app2");

    server.registerApplication(app1);
    server.registerApplication(app2);
    server.start();

    assertThat(makeGetRequest( "/app1/resource"), is(Code.OK));
    assertThat(makeGetRequest( "/app2/resource"), is(Code.UNAUTHORIZED));
  }

  /* Test Exception Mapper isolation */
  @Test
  public void testExceptionMapperIsolation() throws Exception {
    TestApp app1 = new TestApp("/app1");
    TestApp app2 = new TestApp("/app2") {
      @Override
      public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
        config.register(RestResource.class);
        config.register(TestExceptionMapper.class);
      }
    };

    server.registerApplication(app1);
    server.registerApplication(app2);
    server.start();

    assertThat(makeGetRequest( "/app1/exception"), is(Code.INTERNAL_SERVER_ERROR));
    assertThat(makeGetRequest("/app2/exception"), is(Code.ENHANCE_YOUR_CALM));

  }

  /* Test Static Resource Isolation */
  @Test
  public void testStaticResourceIsolation() throws Exception {
    TestApp app1 = new TestApp("/app1");
    TestApp app2 = new TestApp("/app2") {
      @Override
      public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
        config.register(RestResource.class);
        config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "/(index\\.html|)");
      }

      @Override
      protected ResourceCollection getStaticResources() {
        return new ResourceCollection(Resource.newClassPathResource("static"));
      }
    };

    server.registerApplication(app1);
    server.registerApplication(app2);
    server.start();

    assertThat(makeGetRequest("/app1/index.html"), is(Code.NOT_FOUND));
    assertThat(makeGetRequest("/app2/index.html"), is(Code.OK));
  }

  List<URL> getListeners() {
    return Arrays.stream(server.getConnectors())
            .filter(ServerConnector.class::isInstance)
            .map(ServerConnector.class::cast)
            .map(connector -> {
              try {
                String protocol = new HashSet<>(connector.getProtocols())
                        .stream()
                        .map(String::toLowerCase)
                        .anyMatch(s -> s.equals("ssl")) ? "https" : "http";

                int localPort = connector.getLocalPort();

                return new URL(protocol, "localhost", localPort, "");
              } catch (final Exception e) {
                throw new RuntimeException("Malformed listener", e);
              }
            })
            .collect(Collectors.toList());
  }

  @SuppressWarnings("SameParameterValue")
  private HttpStatus.Code makeGetRequest(final String path) throws Exception {
    final HttpGet httpget = new HttpGet(getListeners().get(0).toString() + path);

    try (CloseableHttpClient httpClient = HttpClients.createDefault();
         CloseableHttpResponse response = httpClient.execute(httpget)) {
      return HttpStatus.getCode(response.getStatusLine().getStatusCode());
    }
  }

  @Test
  public void testParseDuplicateUnnamedListeners() throws URISyntaxException {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:4000,http://0.0.0.0:443");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);

    // Should not throw, since http is not considered a listener name.
    List<ApplicationServer.NamedURI> listeners = ApplicationServer.parseListeners(
      config.getList(RestConfig.LISTENERS_CONFIG),
      config.getListenerProtocolMap(),
      0, ApplicationServer.SUPPORTED_URI_SCHEMES, "");

    assertEquals(2, listeners.size());

    assertNull(listeners.get(0).getName());
    assertEquals(new URI("http://0.0.0.0:4000"), listeners.get(0).getUri());

    assertNull(listeners.get(1).getName());
    assertEquals(new URI("http://0.0.0.0:443"), listeners.get(1).getUri());
  }

  @Test(expected = ConfigException.class)
  public void testParseDuplicateNamedListeners() throws URISyntaxException {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG, "INTERNAL://0.0.0.0:4000,INTERNAL://0.0.0.0:443");
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);

    ApplicationServer.parseListeners(
      config.getList(RestConfig.LISTENERS_CONFIG),
      config.getListenerProtocolMap(),
      0, ApplicationServer.SUPPORTED_URI_SCHEMES, "");
  }

  @Test
  public void testParseNamedListeners() throws URISyntaxException {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG, "INTERNAL://0.0.0.0:4000,EXTERNAL://0.0.0.0:443");
    props.put(RestConfig.LISTENER_PROTOCOL_MAP_CONFIG, "INTERNAL:http,EXTERNAL:https");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);

    List<ApplicationServer.NamedURI> namedListeners = ApplicationServer.parseListeners(
      config.getList(RestConfig.LISTENERS_CONFIG),
      config.getListenerProtocolMap(),
      0, ApplicationServer.SUPPORTED_URI_SCHEMES, "");

    assertEquals(2, namedListeners.size());

    assertEquals("internal", namedListeners.get(0).getName());
    assertEquals(new URI("http://0.0.0.0:4000"), namedListeners.get(0).getUri());

    assertEquals("external", namedListeners.get(1).getName());
    assertEquals(new URI("https://0.0.0.0:443"), namedListeners.get(1).getUri());
  }

  @Test
  public void testParseUnnamedListeners() throws URISyntaxException {
    Map<String, Object> props = new HashMap<>();
    props.put(RestConfig.LISTENERS_CONFIG, "http://0.0.0.0:4000,https://0.0.0.0:443");
    RestConfig config = new RestConfig(RestConfig.baseConfigDef(), props);

    List<ApplicationServer.NamedURI> namedListeners = ApplicationServer.parseListeners(
      config.getList(RestConfig.LISTENERS_CONFIG),
      config.getListenerProtocolMap(),
      0, ApplicationServer.SUPPORTED_URI_SCHEMES, "");

    assertEquals(2, namedListeners.size());

    assertNull(namedListeners.get(0).getName());
    assertEquals(new URI("http://0.0.0.0:4000"), namedListeners.get(0).getUri());

    assertNull(namedListeners.get(1).getName());
    assertEquals(new URI("https://0.0.0.0:443"), namedListeners.get(1).getUri());
  }

  // There is additional testing of parseListeners in ApplictionTest

  private static class TestApp extends Application<TestRestConfig> implements AutoCloseable {
    private static final AtomicBoolean SHUTDOWN_CALLED = new AtomicBoolean(true);

    TestApp(String path) {
      this(testConfig, path);
    }

    TestApp(TestRestConfig config, String path) {
      super(config, path);
    }

    @Override
    public void setupResources(final Configurable<?> config, final TestRestConfig appConfig) {
      config.register(RestResource.class);
    }

    @Override
    public void close() throws Exception {
      stop();
    }

    @Override
    public void onShutdown() {
      SHUTDOWN_CALLED.set(true);
    }
  }

  @Path("/")
  @Produces(MediaType.TEXT_PLAIN)
  public static class RestResource {
    @GET
    @Path("/resource")
    public String get() {
      return "Hello";
    }

    @GET
    @Path("/exception")
    public String throwException() throws Throwable {
      throw new Throwable("catch!");
    }
  }

  public static class TestExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable throwable) {

      return Response.status(420)
              .entity(throwable.getMessage())
              .type(MediaType.APPLICATION_JSON)
              .build();
    }
  }
}
