lazy val root = (project in file(".")).
  settings(
    name := "W10Wheel",
    version := "2.7",
    scalaVersion := "2.12.4",
    compileOrder := CompileOrder.JavaThenScala
  )
 
scalacOptions += "-target:jvm-1.8"
 
// http://stackoverflow.com/questions/29696288/how-to-set-class-path-in-manifest-mf-to-custom-classpath
val classPath = Seq(
  ".",
  "lib/jna-4.5.0.jar",
  "lib/jna-platform-4.5.0.jar",
  "lib/logback-classic-1.2.3.jar",
  "lib/logback-core-1.2.3.jar",
  "lib/scala-library.jar",
  "lib/scala-logging_2.12-3.5.0.jar",
  "lib/slf4j-api-1.7.25.jar",
  "lib/swt.jar"
)

packageOptions += Package.ManifestAttributes(
  "Class-Path" -> classPath.mkString(" ")
)
