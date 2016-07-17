lazy val root = (project in file(".")).
  settings(
    name := "W10Wheel",
    version := "0.7.3",
    scalaVersion := "2.11.8"
  )
 
scalacOptions += "-target:jvm-1.8"
 
// http://stackoverflow.com/questions/29696288/how-to-set-class-path-in-manifest-mf-to-custom-classpath
val classPath = Seq(
  ".",
  "lib/jna-4.2.2.jar",
  "lib/jna-platform-4.2.2.jar",
  "lib/logback-classic-1.1.7.jar",
  "lib/logback-core-1.1.7.jar",
  "lib/scala-library.jar",
  "lib/scala-logging_2.11-3.4.0.jar",
  "lib/slf4j-api-1.7.21.jar",
  "lib/swt.jar",
  "lib/win32ex.jar"
)

packageOptions += Package.ManifestAttributes(
  "Class-Path" -> classPath.mkString(" ")
)
