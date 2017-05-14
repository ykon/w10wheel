lazy val root = (project in file(".")).
  settings(
    name := "ReplaceSWT",
    version := "0.3",
    scalaVersion := "2.11.8"
  )
 
scalacOptions += "-target:jvm-1.8"
 
// http://stackoverflow.com/questions/29696288/how-to-set-class-path-in-manifest-mf-to-custom-classpath
val classPath = Seq(
  ".",
  "lib/scala-library.jar"
)

packageOptions += Package.ManifestAttributes(
  "Class-Path" -> classPath.mkString(" ")
)
