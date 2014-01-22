package edu.nrao.vlite

import akka.actor._
import akka.kernel.Bootable
import com.typesafe.config.ConfigFactory

class Simulator extends Bootable {

  val system = ActorSystem("vlite", ConfigFactory.load.getConfig("vlite"))

  def startup {
    val settings = Settings(system)

    if (settings.hostname == settings.controllerHostname)
      system.actorOf(Props[Controller], "controller")
  }

  def shutdown {
    system.shutdown
  }
}
