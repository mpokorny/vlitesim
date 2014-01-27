package edu.nrao.vlite

import akka.actor._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory

class Simulator extends Bootable {

  val version = "20140127.1"

  val system = ActorSystem("vlite", ConfigFactory.load.getConfig("vlite"))

  def startup() {
    println(s"Starting VLITE simulator (v. $version)...")

    val settings = Settings(system)

    if (settings.hostname == settings.controllerHostname)
      system.actorOf(Props[Controller], "controller")
  }

  def shutdown() {
    system.shutdown()
    system.awaitTermination()
    println("Stopped VLITE simulator")
  }
}
