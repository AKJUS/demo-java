package com.saucedemo.selenium.selenium_features;

import static com.google.common.net.MediaType.JPEG;
import static org.openqa.selenium.devtools.events.CdpEventTypes.consoleEvent;
import static org.openqa.selenium.devtools.events.CdpEventTypes.domMutation;
import static org.openqa.selenium.remote.http.HttpMethod.DELETE;
import static org.openqa.selenium.support.ui.ExpectedConditions.presenceOfAllElementsLocatedBy;
import static org.openqa.selenium.support.ui.ExpectedConditions.presenceOfElementLocated;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import com.saucedemo.selenium.TestBase;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.Credentials;
import org.openqa.selenium.HasAuthentication;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.NetworkInterceptor;
import org.openqa.selenium.devtools.v136.browser.Browser;
import org.openqa.selenium.devtools.v136.emulation.Emulation;
import org.openqa.selenium.devtools.v136.network.Network;
import org.openqa.selenium.devtools.v136.network.model.Headers;
import org.openqa.selenium.devtools.v136.performance.Performance;
import org.openqa.selenium.devtools.v136.performance.model.Metric;
import org.openqa.selenium.devtools.v136.runtime.Runtime;
import org.openqa.selenium.logging.HasLogEvents;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.Filter;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Routable;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.support.ui.WebDriverWait;

public class DevToolsTest extends TestBase {
  private static URL APP_URL;

  static {
    try {
      APP_URL = new URL("https://visual-todo-app-84499c59b74b.herokuapp.com");
    } catch (MalformedURLException ignore) {
      // fall off
    }
  }

  WebDriverWait wait;

  @BeforeEach
  public void setup(TestInfo testInfo) {
    Map<String, Object> sauceOptions = defaultSauceOptions(testInfo);
    sauceOptions.put("devtools", true);
    startSession(new ChromeOptions(), sauceOptions);

    wait = new WebDriverWait(driver, Duration.ofSeconds(10));
    driver = new Augmenter().augment(driver);

    ClientConfig clientConfig = ClientConfig.defaultConfig().baseUrl(APP_URL);
    HttpResponse response;
    try (HttpClient client = HttpClient.Factory.createDefault().createClient(clientConfig)) {
      response = client.execute(new HttpRequest(DELETE, APP_URL.toString() + "/items"));
    }
    Assertions.assertEquals(200, response.getStatus());
  }

  @Test
  public void setCookie() {
    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();

    devTools.send(
        Network.setCookie(
            "cheese",
            "gouda",
            Optional.empty(),
            Optional.of("www.selenium.dev"),
            Optional.empty(),
            Optional.of(true),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));

    driver.get("https://www.selenium.dev");
    Cookie cheese = driver.manage().getCookieNamed("cheese");
    Assertions.assertEquals("gouda", cheese.getValue());
  }

  @Test
  public void performanceMetrics() {
    driver.get("https://www.selenium.dev/selenium/web/frameset.html");

    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(Performance.enable(Optional.empty()));

    List<Metric> metricList = devTools.send(Performance.getMetrics());

    Map<String, Number> metrics = new HashMap<>();
    for (Metric metric : metricList) {
      metrics.put(metric.getName(), metric.getValue());
    }

    Assertions.assertTrue(metrics.get("DevToolsCommandDuration").doubleValue() > 0);
    Assertions.assertEquals(12, metrics.get("Frames").intValue());
  }

  @Test
  public void performanceMetricsWithCPUThrottling() {
    driver.get("https://googlechrome.github.io/devtools-samples/jank/");

    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(Performance.enable(Optional.empty()));

    List<Metric> metricList = devTools.send(Performance.getMetrics());
    Metric heapCheckOne =
        metricList.stream()
            .filter(metric -> "JSHeapUsedSize".equals(metric.getName()))
            .findFirst()
            .orElse(new Metric("JSHeapUsedSize", 0));

    devTools.send(Emulation.setCPUThrottlingRate(4));
    for (int i = 0; i < 15; i++) {
      driver.findElement(By.className("add")).click();
    }

    metricList = devTools.send(Performance.getMetrics());
    Metric heapCheckTwo =
        metricList.stream()
            .filter(metric -> "JSHeapUsedSize".equals(metric.getName()))
            .findFirst()
            .orElse(new Metric("JSHeapUsedSize", 0));

    Assertions.assertTrue(heapCheckOne.getValue().intValue() < heapCheckTwo.getValue().intValue());
  }

  @Test
  public void basicAuthenticationCdpApi() {
    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(Network.enable(Optional.of(100000), Optional.of(100000), Optional.of(100000)));

    String encodedAuth = Base64.getEncoder().encodeToString("admin:admin".getBytes());
    Map<String, Object> headers = ImmutableMap.of("Authorization", "Basic " + encodedAuth);

    devTools.send(Network.setExtraHTTPHeaders(new Headers(headers)));

    driver.get("https://the-internet.herokuapp.com/basic_auth");

    Assertions.assertEquals(
        "Congratulations! You must have the proper credentials.",
        driver.findElement(By.tagName("p")).getText());
  }

  @Test
  public void basicAuthenticationBidiApi() {
    Predicate<URI> uriPredicate = uri -> uri.toString().contains("herokuapp.com");
    Supplier<Credentials> authentication = UsernameAndPassword.of("admin", "admin");

    ((HasAuthentication) driver).register(uriPredicate, authentication);

    driver.get("https://the-internet.herokuapp.com/basic_auth");

    String successMessage = "Congratulations! You must have the proper credentials.";
    WebElement elementMessage = driver.findElement(By.tagName("p"));
    Assertions.assertEquals(successMessage, elementMessage.getText());
  }

  @Test
  public void consoleLogs() {
    driver.get("https://www.selenium.dev/selenium/web/bidi/logEntryAdded.html");

    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(Runtime.enable());

    CopyOnWriteArrayList<String> logs = new CopyOnWriteArrayList<>();
    devTools.addListener(
        Runtime.consoleAPICalled(),
        event -> logs.add((String) event.getArgs().get(0).getValue().orElse("")));

    driver.findElement(By.id("consoleLog")).click();

    wait.until(_d -> !logs.isEmpty());
    Assertions.assertEquals("Hello, world!", logs.get(0));
  }

  @Test
  public void jsErrors() {
    driver.get("https://www.selenium.dev/selenium/web/bidi/logEntryAdded.html");

    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(Runtime.enable());

    CopyOnWriteArrayList<JavascriptException> errors = new CopyOnWriteArrayList<>();
    devTools.getDomains().events().addJavascriptExceptionListener(errors::add);

    driver.findElement(By.id("jsException")).click();

    wait.until(_d -> !errors.isEmpty());
    Assertions.assertTrue(errors.get(0).getMessage().contains("Error: Not working"));
  }

  @Test
  public void waitForDownload() {
    driver.get("https://www.selenium.dev/selenium/web/downloads/download.html");

    DevTools devTools = ((HasDevTools) driver).getDevTools();
    devTools.createSession();
    devTools.send(
        Browser.setDownloadBehavior(
            Browser.SetDownloadBehaviorBehavior.ALLOWANDNAME,
            Optional.empty(),
            Optional.of(""),
            Optional.of(true)));

    AtomicBoolean completed = new AtomicBoolean(false);
    devTools.addListener(
        Browser.downloadProgress(),
        e -> completed.set(Objects.equals(e.getState().toString(), "completed")));

    driver.findElement(By.id("file-2")).click();

    Assertions.assertDoesNotThrow(() -> wait.until(_d -> completed));
  }

  @Test
  public void mutatedElements() {
    driver.get("https://www.selenium.dev/selenium/web/dynamic.html");

    CopyOnWriteArrayList<WebElement> mutations = new CopyOnWriteArrayList<>();
    ((HasLogEvents) driver).onLogEvent(domMutation(e -> mutations.add(e.getElement())));

    driver.findElement(By.id("reveal")).click();

    wait.until(_d -> !mutations.isEmpty());
    Assertions.assertEquals(mutations.get(0), driver.findElement(By.id("revealed")));
  }

  @Test
  public void elementsMutation() {
    driver.get("https://the-internet.herokuapp.com/dynamic_controls");

    CopyOnWriteArrayList<WebElement> mutations = new CopyOnWriteArrayList<>();
    ((HasLogEvents) driver).onLogEvent(domMutation(e -> mutations.add(e.getElement())));

    driver.findElement(By.cssSelector("#checkbox-example > button")).click();
    wait.until(_d -> !mutations.isEmpty());

    driver.findElement(By.cssSelector("#checkbox-example > button")).click();
    wait.until(_d -> mutations.size() > 1);

    Assertions.assertTrue(mutations.size() >= 2);
  }

  @Test
  public void consoleLogsBidiApi() {
    driver.get("https://www.selenium.dev/selenium/web/bidi/logEntryAdded.html");

    CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
    ((HasLogEvents) driver).onLogEvent(consoleEvent(e -> messages.add(e.getMessages().get(0))));

    driver.findElement(By.id("consoleLog")).click();
    driver.findElement(By.id("consoleError")).click();

    wait.until(_d -> messages.size() > 1);
    Assertions.assertTrue(messages.contains("Hello, world!"));
    Assertions.assertTrue(messages.contains("I am console error"));
  }

  @Test
  public void recordResponse() {
    CopyOnWriteArrayList<String> contentType = new CopyOnWriteArrayList<>();

    try (NetworkInterceptor ignored =
        new NetworkInterceptor(
            driver,
            (Filter)
                next ->
                    req -> {
                      HttpResponse res = next.execute(req);
                      contentType.add(res.getHeader("Content-Type"));
                      return res;
                    })) {
      driver.get("https://www.selenium.dev/selenium/web/blank.html");
      wait.until(_d -> contentType.size() > 1);
    }

    Assertions.assertEquals("text/html; charset=utf-8", contentType.get(0));
  }

  @Test
  public void transformResponses() {
    try (NetworkInterceptor ignored =
        new NetworkInterceptor(
            driver,
            Route.matching(req -> true)
                .to(
                    () ->
                        req ->
                            new HttpResponse()
                                .setStatus(200)
                                .addHeader("Content-Type", MediaType.HTML_UTF_8.toString())
                                .setContent(Contents.utf8String("Creamy, delicious cheese!"))))) {

      driver.get("https://www.selenium.dev/selenium/web/blank.html");
    }

    WebElement body = driver.findElement(By.tagName("body"));
    Assertions.assertEquals("Creamy, delicious cheese!", body.getText());
  }

  @Test
  void replaceImage() throws IOException {
    String item = "Buy rice";
    Path path = Paths.get("src/test/resources/cat-and-dog.jpg");
    byte[] catAndDog = Files.readAllBytes(path);
    Routable replaceImage =
        Route.matching(req -> req.getUri().contains("picsum.photos"))
            .to(
                () ->
                    req ->
                        new HttpResponse()
                            .addHeader("Content-Type", JPEG.toString())
                            .setContent(Contents.bytes(catAndDog)));

    try (NetworkInterceptor ignore = new NetworkInterceptor(driver, replaceImage)) {
      driver.get(APP_URL.toString());

      String inputFieldLocator = "input[data-testid='new-item-text']";
      WebElement inputField =
          wait.until(presenceOfElementLocated(By.cssSelector(inputFieldLocator)));
      inputField.sendKeys(item);

      driver.findElement(By.cssSelector("button[data-testid='new-item-button']")).click();

      String itemLocator = String.format("div[data-testid='%s']", item);
      List<WebElement> addedItem =
          wait.until(presenceOfAllElementsLocatedBy(By.cssSelector(itemLocator)));

      Assertions.assertEquals(1, addedItem.size());
    }
  }

  @Test
  void replacingResponse() {
    String item = "Clean the bathroom";
    String mockedItem = "Go to the park";

    Routable apiPost =
        Route.matching(
                req -> req.getUri().contains("items") && req.getMethod().equals(HttpMethod.POST))
            .to(
                () ->
                    req ->
                        new HttpResponse()
                            .addHeader("Content-Type", "application/json; charset=utf-8")
                            .setStatus(200)
                            .setContent(
                                Contents.asJson(
                                    ImmutableMap.of(
                                        "id",
                                        "f2a5514c-f451-43a6-825c-8753a2566d6e",
                                        "name",
                                        mockedItem,
                                        "completed",
                                        false))));

    try (NetworkInterceptor ignore = new NetworkInterceptor(driver, apiPost)) {
      driver.get(APP_URL.toString());

      String inputFieldLocator = "input[data-testid='new-item-text']";
      WebElement inputField =
          wait.until(presenceOfElementLocated(By.cssSelector(inputFieldLocator)));
      inputField.sendKeys(item);

      driver.findElement(By.cssSelector("button[data-testid='new-item-button']")).click();

      String itemLocator = String.format("div[data-testid='%s']", mockedItem);
      List<WebElement> addedItem =
          wait.until(presenceOfAllElementsLocatedBy(By.cssSelector(itemLocator)));

      Assertions.assertEquals(1, addedItem.size());
    }
  }
}
