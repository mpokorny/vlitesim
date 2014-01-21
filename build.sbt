import akka.sbt.AkkaKernelPlugin
import akka.sbt.AkkaKernelPlugin.Dist
import play.Project._

name := """vlitesim"""

version := "1.0"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.2",
  "com.typesafe.akka" %% "akka-remote" % "2.2.3",
  "com.typesafe.akka" %% "akka-kernel" % "2.2.3",
  "org.scalatest" %% "scalatest" % "2.0" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test",
  "joda-time" % "joda-time" % "2.3",
  "org.joda" % "joda-convert" % "1.5",
  "com.typesafe" % "config" % "1.2.0"
)

//playScalaSettings

AkkaKernelPlugin.distSettings

(AkkaKernelPlugin.additionalLibs in Dist) := Seq(file("lib/libjnetpcap.so"))

scalacOptions ++= Seq("-deprecation")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")
