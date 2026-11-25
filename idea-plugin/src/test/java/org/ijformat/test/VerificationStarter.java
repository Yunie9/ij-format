package org.ijformat.test;

import com.intellij.configurationStore.StoreUtil;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableEP;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.psi.codeStyle.CodeStyleSchemes;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;

import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;

/** Installed only in the isolated integration-test IDE. */
public final class VerificationStarter implements ApplicationStarter {
  @Override
  public int getRequiredModality() {
    return NOT_IN_EDT;
  }

  @Override
  public void main(List<String> args) {
    try {
      Path state = Path.of(args.get(1));
      var projects = ProjectManager.getInstance();
      System.out.println("TEST_OPEN_PROJECTS " + projects.getOpenProjects().length);
      if (args.contains("project-first")) {
        openFixture(state, projects);
        await(state.resolve("finish"));
        System.exit(0);
        return;
      }
      ApplicationManager.getApplication().invokeAndWait(() -> ApplicationManager.getApplication()
          .getMessageBus().syncPublisher(AppLifecycleListener.TOPIC).welcomeScreenDisplayed());
      verifySettings(state);
      await(state.resolve("open-project"));
      openFixture(state, projects);

      await(state.resolve("switch-scheme"));
      ApplicationManager.getApplication().invokeAndWait(() -> {
        var schemes = CodeStyleSchemes.getInstance();
        var scheme = schemes.findSchemeByName("McpSecond");
        if (scheme == null) {
          throw new IllegalStateException("Test application-level scheme was not loaded");
        }
        schemes.setCurrentScheme(scheme);
      });
      System.out.println("TEST_SCHEME_CHANGED");
      await(state.resolve("finish"));
      System.exit(0);
    }
    catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  public static final class WelcomeProbe implements AppLifecycleListener {
    @Override
    public void welcomeScreenDisplayed() {
      System.out.println("TEST_WELCOME_OPEN_PROJECTS " + ProjectManager.getInstance().getOpenProjects().length);
    }
  }

  private static void openFixture(Path state, ProjectManager projects) {
    var project = ProjectManagerEx.getInstanceEx().openProject(state.resolve("project"),
        new OpenProjectTask(false, null, false, false));
    if (project == null || projects.getOpenProjects().length != 1) {
      throw new IllegalStateException("Could not open the fixture project");
    }
    ApplicationManager.getApplication().invokeAndWait(() -> {
      var manager = CodeStyleSettingsManager.getInstance(project);
      var settings = manager.createSettings();
      settings.getCommonSettings("JAVA").getIndentOptions().INDENT_SIZE = 10;
      settings.getCommonSettings("kotlin").getIndentOptions().INDENT_SIZE = 10;
      manager.setMainProjectCodeStyle(settings);
      manager.USE_PER_PROJECT_SETTINGS = true;
      System.out.println("TEST_PROJECT_INDENT " +
          manager.getCurrentSettings().getCommonSettings("JAVA").getIndentOptions().INDENT_SIZE);
    });
  }

  private static void verifySettings(Path state) throws Exception {
    var application = ApplicationManager.getApplication();
    var configurable = ExtensionPointName.<ConfigurableEP<Configurable>>create("com.intellij.applicationConfigurable")
        .getExtensionList().stream()
        .filter(extension -> "org.ijformat.mcp.settings".equals(extension.id) && "tools".equals(extension.parentId))
        .findFirst().orElseThrow().createConfigurable();
    await(state.resolve("occupy-workers"));
    var oldWorkers = workerPool(configurable);
    checkPool(oldWorkers, 4, 4, 20);
    application.invokeAndWait(() -> {
      // Hold the formatter's EDT work until Apply has run, without test hooks in the server.
      System.out.println("TEST_MCP_EDT_HELD");
      waitUntil(() -> oldWorkers.getActiveCount() == 4, "four running HTTP requests");
      System.out.println("TEST_MCP_WORKERS_BUSY");
      waitUntil(() -> oldWorkers.getQueue().size() == 3, "three queued HTTP requests");
      var panel = configurable.createComponent();
      if (!"ij-format".equals(configurable.getDisplayName())) {
        throw new IllegalStateException("Unexpected settings display name");
      }
      var fields = components(panel, JTextField.class);
      checkFields(fields, "4", "4", "20");
      fields.get(0).setText("5");
      expectInvalid(configurable, "Minimum workers must be at most maximum workers.");
      fields.get(0).setText("1");
      fields.get(1).setText("2");
      fields.get(2).setText("0");
      expectInvalid(configurable, "Waiting queue capacity must be between 1 and 256.");
      fields.get(2).setText("1");
      apply(configurable);
      if (configurable.isModified() || !hasLabel(panel, "Workers 1–2, waiting queue 1")) {
        throw new IllegalStateException("The updated limits were not shown after Apply");
      }
      checkPool(oldWorkers, 1, 2, 0);
      if (oldWorkers.getActiveCount() != 4 || oldWorkers.getQueue().size() != 3 || oldWorkers.getQueue().offer(() -> {})) {
        throw new IllegalStateException("Shrink did not preserve the running and queued HTTP requests");
      }
      configurable.disposeUIResources();
    });
    StoreUtil.saveSettings(application, true);
    if (workerPool(configurable) != oldWorkers) {
      throw new IllegalStateException("Apply replaced the running worker pool");
    }
    System.out.println("TEST_MCP_LIMITS_SAVED");
    await(state.resolve("expand-limits"));
    waitUntil(() -> oldWorkers.getPoolSize() <= 2, "excess HTTP workers to retire");
    checkPool(oldWorkers, 1, 2, 1);
    application.invokeAndWait(() -> {
      var panel = configurable.createComponent();
      var fields = components(panel, JTextField.class);
      checkFields(fields, "1", "2", "1");
      fields.get(0).setText("3");
      fields.get(1).setText("5");
      fields.get(2).setText("6");
      apply(configurable);
      if (!hasLabel(panel, "Workers 3–5, waiting queue 6")) {
        throw new IllegalStateException("The expanded limits were not shown after Apply");
      }
      configurable.disposeUIResources();
    });
    if (workerPool(configurable) != oldWorkers) {
      throw new IllegalStateException("Expansion replaced the worker pool");
    }
    checkPool(oldWorkers, 3, 5, 6);
    System.out.println("TEST_MCP_LIMITS_EXPANDED");
    await(state.resolve("disable-mcp"));
    application.invokeAndWait(() -> {
      var panel = configurable.createComponent();
      checkFields(components(panel, JTextField.class), "3", "5", "6");
      var enabled = component(panel, JCheckBox.class);
      var copy = component(panel, JButton.class);
      var copyPath = components(panel, JButton.class).stream()
          .filter(button -> "Copy CLI Config Path".equals(button.getText())).findFirst().orElseThrow();
      if (!enabled.isSelected() || !copy.isEnabled() || !copyPath.isEnabled()) {
        throw new IllegalStateException("MCP settings did not show a running server");
      }
      enabled.setSelected(false);
      apply(configurable);
      if (copy.isEnabled() || copyPath.isEnabled() || configurable.isModified()) {
        throw new IllegalStateException("MCP settings did not show a disabled server");
      }
    });
    if (!oldWorkers.isShutdown()) {
      throw new IllegalStateException("Disabling MCP retained the old pool");
    }
    StoreUtil.saveSettings(application, true);
    System.out.println("TEST_MCP_DISABLED");
    await(state.resolve("enable-mcp"));
    application.invokeAndWait(() -> {
      configurable.disposeUIResources();
      var panel = configurable.createComponent();
      var enabled = component(panel, JCheckBox.class);
      var copy = component(panel, JButton.class);
      var copyPath = components(panel, JButton.class).stream()
          .filter(button -> "Copy CLI Config Path".equals(button.getText())).findFirst().orElseThrow();
      if (enabled.isSelected()) {
        throw new IllegalStateException("MCP settings did not reload the disabled state");
      }
      enabled.setSelected(true);
      apply(configurable);
      if (!hasLabel(panel, "Workers 3–5, waiting queue 6")) {
        throw new IllegalStateException("The restarted server did not show the new active limits");
      }
      copy.doClick();
      String json = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
      copyPath.doClick();
      String path = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
      try {
        Files.writeString(state.resolve("copied.json"), json);
        Files.writeString(state.resolve("copied-path.txt"), path);
      }
      catch (Exception e) {
        throw new IllegalStateException("Could not capture copied MCP JSON", e);
      }
      configurable.disposeUIResources();
    });
    checkPool(workerPool(configurable), 3, 5, 6);
    System.out.println("TEST_MCP_REENABLED");
  }

  private static ThreadPoolExecutor workerPool(Configurable configurable) throws ReflectiveOperationException {
    // Inspect the real executor in the isolated IDE without exposing it through the plugin API.
    var serverType = configurable.getClass().getClassLoader().loadClass("org.ijformat.mcp.LocalMcpServer");
    var server = ApplicationManager.getApplication().getService(serverType);
    var runningField = serverType.getDeclaredField("running");
    runningField.setAccessible(true);
    var running = runningField.get(server);
    var workersField = running.getClass().getDeclaredField("workers");
    workersField.setAccessible(true);
    return (ThreadPoolExecutor) workersField.get(running);
  }

  private static void checkPool(ThreadPoolExecutor pool, int min, int max, int queueCapacity) {
    if (pool.getCorePoolSize() != min || pool.getMaximumPoolSize() != max ||
        pool.getQueue().remainingCapacity() != queueCapacity) {
      throw new IllegalStateException("Unexpected worker pool limits");
    }
  }

  private static void waitUntil(BooleanSupplier ready, String description) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Timed out waiting for " + description);
      }
      try {
        Thread.sleep(20);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted waiting for " + description, e);
      }
    }
  }

  private static void checkFields(List<JTextField> fields, String... expected) {
    if (!fields.stream().map(JTextField::getText).toList().equals(List.of(expected))) {
      throw new IllegalStateException("Unexpected worker settings values");
    }
  }

  private static boolean hasLabel(Component root, String text) {
    return components(root, JLabel.class).stream().anyMatch(label -> text.equals(label.getText()));
  }

  private static void expectInvalid(Configurable configurable, String message) {
    try {
      configurable.apply();
    }
    catch (ConfigurationException expected) {
      if (message.equals(expected.getMessage())) return;
      throw new IllegalStateException("Unexpected validation message", expected);
    }
    throw new IllegalStateException("Invalid setting was accepted");
  }

  private static void apply(Configurable configurable) {
    try {
      configurable.apply();
    }
    catch (ConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T extends Component> T component(Component root, Class<T> type) {
    if (type.isInstance(root)) return type.cast(root);
    if (root instanceof Container container) {
      for (var child : container.getComponents()) {
        var result = component(child, type);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static <T extends Component> List<T> components(Component root, Class<T> type) {
    var found = new ArrayList<T>();
    if (type.isInstance(root)) found.add(type.cast(root));
    if (root instanceof Container container) {
      for (var child : container.getComponents()) found.addAll(components(child, type));
    }
    return found;
  }

  private static void await(Path trigger) throws InterruptedException {
    while (!Files.exists(trigger)) {
      Thread.sleep(50);
    }
  }
}
