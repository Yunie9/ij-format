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
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;

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
    await(state.resolve("disable-mcp"));
    var configurable = ExtensionPointName.<ConfigurableEP<Configurable>>create("com.intellij.applicationConfigurable")
        .getExtensionList().stream()
        .filter(extension -> "org.ijformat.mcp.settings".equals(extension.id) && "tools".equals(extension.parentId))
        .findFirst().orElseThrow().createConfigurable();
    application.invokeAndWait(() -> {
      var panel = configurable.createComponent();
      var enabled = component(panel, JCheckBox.class);
      var copy = component(panel, JButton.class);
      if (!enabled.isSelected() || !copy.isEnabled()) {
        throw new IllegalStateException("MCP settings did not show a running server");
      }
      enabled.setSelected(false);
      apply(configurable);
      if (copy.isEnabled() || configurable.isModified()) {
        throw new IllegalStateException("MCP settings did not show a disabled server");
      }
    });
    StoreUtil.saveSettings(application, true);
    System.out.println("TEST_MCP_DISABLED");
    await(state.resolve("enable-mcp"));
    application.invokeAndWait(() -> {
      configurable.disposeUIResources();
      var panel = configurable.createComponent();
      var enabled = component(panel, JCheckBox.class);
      var copy = component(panel, JButton.class);
      if (enabled.isSelected()) {
        throw new IllegalStateException("MCP settings did not reload the disabled state");
      }
      enabled.setSelected(true);
      apply(configurable);
      copy.doClick();
      String json = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
      try {
        Files.writeString(state.resolve("copied.json"), json);
      }
      catch (Exception e) {
        throw new IllegalStateException("Could not capture copied MCP JSON", e);
      }
      configurable.disposeUIResources();
    });
    System.out.println("TEST_MCP_REENABLED");
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

  private static void await(Path trigger) throws InterruptedException {
    while (!Files.exists(trigger)) {
      Thread.sleep(50);
    }
  }
}
