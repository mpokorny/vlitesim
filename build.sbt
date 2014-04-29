//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.SbtNativePackager._

name := """vlitesim"""

version := "1.1"

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
  """addJava "-Dakka.remote.netty.tcp.hostname=${VLITE_HOSTNAME:-$HOSTNAME}"""",
  """addJava "-Dconfig.file=${app_home}/../conf/application.conf"""",
  """export LD_LIBRARY_PATH=${app_home}/../lib"""
)

val downloadLicense = taskKey[File]("Downloads the license file.")

downloadLicense := {
  val location = target.value / "downloads" / "GPLv3"
  location.getParentFile.mkdirs()
  IO.download(url("https://www.gnu.org/licenses/gpl-3.0.txt"), location)
  location
}

mappings in Universal ++= {
  ((file("src/main/resources") * "*").get map { f => f -> ("conf/" + f.name) }) ++
  ((file("bin") * "*").get map { f => f -> ("bin/" + f.name) }) :+
  (downloadLicense.value -> "COPYING")
}
