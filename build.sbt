name := """vlitesim"""

version := "1.0"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.1",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.1",
  "org.scalatest" %% "scalatest" % "2.0" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test",
  "joda-time" % "joda-time" % "2.3",
  "org.joda" % "joda-convert" % "1.5",
  "net.sf.jopt-simple" % "jopt-simple" % "4.6"
)

scalacOptions ++= Seq("-deprecation")

resolvers += "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")
