import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.SbtNativePackager._

name := """vlitesim"""

version := "1.0"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.4",
  "com.typesafe.akka" %% "akka-remote" % "2.2.4",
  "com.typesafe.akka" %% "akka-kernel" % "2.2.4",
  "joda-time" % "joda-time" % "2.3",
  "org.joda" % "joda-convert" % "1.5",
  "com.typesafe" % "config" % "1.2.0",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.4" % "test",
  "org.scalatest" %% "scalatest" % "2.0" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test"
)

packageArchetype.java_application

scalacOptions ++= Seq("-deprecation")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")

mainClass in Compile := Some("akka.kernel.Main")

bashScriptConfigLocation := Some("${app_home}/../conf/scriptargs")

bashScriptExtraDefines ++= Seq(
  """addApp "edu.nrao.vlite.Simulator"""",
  """addJava "-Dakka.home=${app_home}/.."""",
  """export LD_LIBRARY_PATH=${app_home}/../lib"""
)

val downloadLicense = taskKey[File]("Downloads the license file.")

downloadLicense := {
  val location = target.value / "downloads" / "LICENSE"
  location.getParentFile.mkdirs()
  IO.download(url("https://www.gnu.org/licenses/gpl-3.0.txt"), location)
  location
}

mappings in Universal ++= {
  ((file("src/main/resources") * "*").get map { f => f -> ("conf/" + f.name) }) ++
  ((file("bin") * "*").get map { f => f -> ("bin/" + f.name) }) :+
  (downloadLicense.value -> "COPYING")
}
