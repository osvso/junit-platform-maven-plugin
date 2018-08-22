package de.sormuras.junit.platform.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.apache.maven.artifact.DependencyResolutionRequiredException;

class JUnitPlatformConsoleStarter implements IntSupplier {

  private final Configuration configuration;
  private final ModularWorld world;

  JUnitPlatformConsoleStarter(Configuration configuration, ModularWorld world) {
    this.configuration = configuration;
    this.world = world;
  }

  @Override
  public int getAsInt() {
    var log = configuration.getLog();
    var target = Paths.get(configuration.getMavenProject().getBuild().getDirectory());
    var timeout = configuration.getTimeout().toSeconds();
    var reports = configuration.getReportsPath();
    var mainMod = world.getTestModuleReference();
    var testMod = world.getTestModuleReference();

    // Prepare the process builder
    var builder = new ProcessBuilder();
    // builder.directory(program.getParent().toFile());
    builder.redirectError(target.resolve("junit-console-launcher.err.txt").toFile());
    builder.redirectOutput(target.resolve("junit-console-launcher.out.txt").toFile());
    builder.redirectInput(ProcessBuilder.Redirect.INHERIT);

    // "java[.exe]"
    builder.command().add(getCurrentJavaExecutablePath().toString());

    // Supply standard options for Java
    // https://docs.oracle.com/javase/10/tools/java.htm
    if (!mainMod.isPresent() && !testMod.isPresent()) {
      builder.command().add("--class-path");
      builder.command().add(createModulePathArgument());
      builder.command().add("org.junit.platform.console.ConsoleLauncher");
    }
    if (testMod.isPresent()) {
      builder.command().add("--module-path");
      builder.command().add(createModulePathArgument());
      builder.command().add("--add-modules");
      builder.command().add("ALL-MODULE-PATH,ALL-DEFAULT");
      builder.command().add("--module");
      builder.command().add("org.junit.platform.console");
    }

    // Now append console launcher options
    // See https://junit.org/junit5/docs/snapshot/user-guide/#running-tests-console-launcher-options
    builder.command().add("--disable-ansi-colors");
    if (configuration.isStrict()) {
      builder.command().add("--fail-if-no-tests");
    }
    configuration.getTags().forEach(builder.command()::add);
    configuration
        .getParameters()
        .forEach((key, value) -> builder.command().add(createConfigArgument(key, value)));
    reports.ifPresent(
        path -> {
          builder.command().add("--reports-dir");
          builder.command().add(path.toString());
        });

    if (testMod.isPresent()) {
      builder.command().add("--select-module");
      builder.command().add(testMod.get().descriptor().name());
    } else {
      builder.command().add("--scan-class-path");
    }

    // Start
    log.debug("Starting process (timeout=" + timeout + ")...");
    builder.command().forEach(log::debug);
    try {
      var process = builder.start();
      var ok = process.waitFor(timeout, TimeUnit.SECONDS);
      if (!ok) {
        log.error("Global timeout reached: " + timeout + " second(s)");
        return -2;
      }
      return process.exitValue();
    } catch (IOException | InterruptedException e) {
      log.error("Executing process failed", e);
      return -1;
    }
  }

  private String createConfigArgument(String key, String value) {
    return "--config=\"" + key + "\"=\"" + value + "\"";
  }

  private String createModulePathArgument() {
    var log = configuration.getLog();
    var project = configuration.getMavenProject();
    var elements = new ArrayList<String>();
    try {
      for (var element : project.getTestClasspathElements()) {
        var path = Paths.get(element).toAbsolutePath().normalize();
        if (Files.notExists(path)) {
          log.debug("   X " + path + " // doesn't exist");
          continue;
        }
        log.debug("  -> " + path);
        elements.add(path.toString());
      }
    } catch (DependencyResolutionRequiredException e) {
      throw new IllegalStateException("Resolving test class-path elements failed", e);
    }
    return String.join(File.pathSeparator, elements);
  }

  private static Path getCurrentJavaExecutablePath() {
    var path = ProcessHandle.current().info().command().map(Paths::get).orElseThrow();
    return path.normalize().toAbsolutePath();
  }
}
