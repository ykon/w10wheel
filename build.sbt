import java.nio.charset.StandardCharsets
import java.nio.file.Files

val stageJpackageInput = taskKey[File]("Stage application JAR and runtime dependencies for jpackage")

lazy val root = (project in file("."))
  .settings(
    name := "W10Wheel",
    version := "2.8.1",
    scalaVersion := "3.9.0",
    Compile / compileOrder := CompileOrder.JavaThenScala,
    scalacOptions ++= Seq("-java-output-version", "25", "-deprecation", "-feature", "-unchecked"),
    javacOptions ++= Seq("--release", "25"),
    Compile / compile / javacOptions += "-Xlint:all",
    Compile / mainClass := Some("hooktest.W10Wheel"),
    run / fork := true,
    run / javaOptions += "--enable-native-access=ALL-UNNAMED",
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
      "org.slf4j" % "slf4j-api" % "2.0.20",
      "ch.qos.logback" % "logback-classic" % "1.6.8",
      "net.java.dev.jna" % "jna" % "5.19.1",
      "net.java.dev.jna" % "jna-platform" % "5.19.1",
      // Swing look and feel (light and dark themes, Windows 11 style)
      "com.formdev" % "flatlaf" % "3.8"
    ),
    dependencyOverrides += "org.slf4j" % "slf4j-api" % "2.0.20",
    stageJpackageInput := Def.uncached {
      val conv = fileConverter.value
      val jpackageDir = baseDirectory.value / "target" / "jpackage"
      val inputDir = jpackageDir / "input"
      IO.delete(inputDir)
      IO.createDirectory(inputDir)

      val mainJarFile = conv.toPath((Compile / packageBin).value).toFile
      IO.copyFile(mainJarFile, inputDir / mainJarFile.getName)

      (Runtime / dependencyClasspath).value.foreach { entry =>
        val file = conv.toPath(entry.data).toFile
        if (file.isFile && file.getName.endsWith(".jar")) {
          IO.copyFile(file, inputDir / file.getName)
        }
      }

      Files.writeString((jpackageDir / "main-jar.txt").toPath, mainJarFile.getName, StandardCharsets.UTF_8)
      Files.writeString((jpackageDir / "version.txt").toPath, version.value, StandardCharsets.UTF_8)
      inputDir
    }
  )
